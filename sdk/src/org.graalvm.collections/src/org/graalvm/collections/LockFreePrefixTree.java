/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.graalvm.collections;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * Thread-safe and lock-free prefix-tree implementation in which keys are sequences of 64-bit
 * values, and the values are 64-bit values. The LockFreePrefixTree supports the same operations as
 * the PrefixTree as follows:
 * <p>
 * The LockFreePrefixTree supports a single operation {@code root}, which returns the root node. The
 * nodes support the following operations: {@code at} to obtain a child node, {@code value} to
 * obtain the value at the current node, {@code setValue} to atomically set the value, and
 * {@code incValue} to atomically increment the value.
 * <p>
 *
 * The LockFreePrefix tree represents a Tree of nodes of class{@code Node}, with each node having a
 * key and an atomic reference array of children. The underlying {@code children} structure is
 * represented as a LinearArray if the number of children is under a threshold, and represented by a
 * hash table once the threshold is reached.
 *
 * Any additions or accesses to the datastructure are done using the {@code at} function. The
 * {@code at} function takes a key value as a parameter and either returns an already existing node
 * or inserts a new node and returns it. The function may cause the underlying AtomicReferenceArray
 * to grow in size, either with {@code tryResizeLinear} or {@code tryResizeHash}. Insertion of new
 * nodes is always done with the CAS operation, to ensure atomic updates and guarantee the progress
 * of at least a single thread in the execution. Additionally, any growth operations occur
 * atomically, as we perform a CAS with the reference to the Array to a new, freshly allocated array
 * object.
 */
public class LockFreePrefixTree {
    public static class Node extends AtomicLong {

        public interface Visitor<R> {
            R visit(Node n, List<R> childResults);
        }

        private static final class LinearChildren extends AtomicReferenceArray<Node> {
            LinearChildren(int length) {
                super(length);
            }
        }

        private static final class HashChildren extends AtomicReferenceArray<Node> {
            HashChildren(int length) {
                super(length);
            }
        }

        private static final class FrozenNode extends Node {
            FrozenNode() {
                super(-1);
            }
        }

        private static final FrozenNode FROZEN_NODE = new FrozenNode();

        // Requires: INITIAL_HASH_NODE_SIZE >= MAX_LINEAR_NODE_SIZE
        // otherwise we have an endless loop
        private static final int INITIAL_LINEAR_NODE_SIZE = 2;

        private static final int INITIAL_HASH_NODE_SIZE = 16;

        private static final int MAX_LINEAR_NODE_SIZE = 8;

        private static final int MAX_HASH_SKIPS = 10;

        private static final AtomicReferenceFieldUpdater<Node, AtomicReferenceArray> childrenUpdater = AtomicReferenceFieldUpdater.newUpdater(Node.class, AtomicReferenceArray.class, "children");

        private final long key;

        private volatile AtomicReferenceArray<Node> children;

        public Node(long key) {
            this.key = key;
        }

        public long value() {
            return get();
        }

        public long getKey() {
            return this.key;
        }

        public void setValue(long value) {
            set(value);
        }

        public long incValue() {
            return incrementAndGet();
        }

        @SuppressWarnings("unchecked")
        public Node at(long key) {
            ensureChildren();

            while (true) {
                AtomicReferenceArray<Node> children0 = readChildren();
                if (children0 instanceof LinearChildren) {
                    // Find first empty slot.
                    Node newChild = getOrAddLinear(key, children0);
                    if (newChild != null) {
                        return newChild;
                    } else {
                        // Children array is full, we need to resize.
                        tryResizeLinear(children0);
                    }
                } else {
                    // children0 instanceof HashChildren.
                    Node newChild = getOrAddHash(key, children0);
                    if (newChild != null) {
                        return newChild;
                    } else {
                        // Case for growth: the MAX_HASH_SKIPS have been exceeded.
                        tryResizeHash(children0);
                    }
                }
            }
        }

        // Postcondition: if return value is null, then no subsequent mutations will be done on the
        // array object ( the children array is full)
        private Node getOrAddLinear(long key, AtomicReferenceArray<Node> childrenArray) {
            for (int i = 0; i < childrenArray.length(); i++) {
                Node child = read(childrenArray, i);
                if (child == null) {
                    Node newChild = new Node(key);
                    if (cas(childrenArray, i, null, newChild)) {
                        return newChild;
                    } else {
                        // We need to check if the failed CAS was due to another thread inserting
                        // this key.
                        Node child1 = read(childrenArray, i);
                        if (child1.getKey() == key) {
                            return child1;
                        } else {
                            continue;
                        }
                    }
                } else if (child.getKey() == key) {
                    return child;
                }
            }
            // Array is full, triggers resize.
            return null;
        }

        // Precondition: childrenArray is full.
        private void tryResizeLinear(AtomicReferenceArray<Node> childrenArray) {
            AtomicReferenceArray<Node> newChildrenArray;
            if (childrenArray.length() < MAX_LINEAR_NODE_SIZE) {
                newChildrenArray = new LinearChildren(2 * childrenArray.length());
                for (int i = 0; i < childrenArray.length(); i++) {
                    Node toCopy = read(childrenArray, i);
                    write(newChildrenArray, i, toCopy);
                }
            } else {
                newChildrenArray = new HashChildren(INITIAL_HASH_NODE_SIZE);
                for (int i = 0; i < childrenArray.length(); i++) {
                    Node toCopy = read(childrenArray, i);
                    addChildToLocalHash(toCopy, newChildrenArray);
                }
            }
            childrenUpdater.compareAndSet(this, childrenArray, newChildrenArray);
        }

        private Node getOrAddHash(long key, AtomicReferenceArray<Node> hashTable) {
            int index = hash(key) % hashTable.length();
            int skips = 0;
            while (true) {
                Node node0 = read(hashTable, index);
                if (node0 == null) {
                    Node newNode = new Node(key);
                    if (cas(hashTable, index, null, newNode)) {
                        return newNode;
                    } else {
                        // Rechecks same index spot if the node has been inserted by other thread.
                        continue;
                    }
                } else if (node0 != FROZEN_NODE && node0.getKey() == key) {
                    return node0;
                }
                index = (index + 1) % hashTable.length();
                skips++;
                if (skips > MAX_HASH_SKIPS) {
                    // Returning null triggers hash growth.
                    return null;
                }
            }
        }

        // This method can only get called in the grow hash function, or when converting from linear
        // to hash, meaning it is only exposed to a SINGLE thread
        // Precondition: reachable from exactly one thread
        private void addChildToLocalHash(Node node, AtomicReferenceArray<Node> hashTable) {
            int index = hash(node.getKey()) % hashTable.length();
            while (read(hashTable, index) != null) {
                index = (index + 1) % hashTable.length();
            }
            write(hashTable, index, node);
        }

        private void tryResizeHash(AtomicReferenceArray<Node> children0) {
            freezeHash(children0);
            // All elements of children0 are non-null => ensures no updates are made to old children
            // while we are copying to new children.
            AtomicReferenceArray<Node> newChildrenHash = new HashChildren(2 * children0.length());
            for (int i = 0; i < children0.length(); i++) {
                Node toCopy = read(children0, i);
                if (toCopy != FROZEN_NODE) {
                    addChildToLocalHash(toCopy, newChildrenHash);
                }
            }
            casChildren(children0, newChildrenHash);
        }

        // Postcondition: Forall element in childrenHash => element != null.
        private void freezeHash(AtomicReferenceArray<Node> childrenHash) {
            for (int i = 0; i < childrenHash.length(); i++) {
                if (read(childrenHash, i) == null) {
                    cas(childrenHash, i, null, FROZEN_NODE);
                }
            }
        }

        private boolean cas(AtomicReferenceArray<Node> childrenArray, int i, Node expected, Node updated) {
            return childrenArray.compareAndSet(i, expected, updated);
        }

        private Node read(AtomicReferenceArray<Node> childrenArray, int i) {
            return childrenArray.get(i);
        }

        private void write(AtomicReferenceArray<Node> childrenArray, int i, Node newNode) {
            childrenArray.set(i, newNode);
        }

        private void ensureChildren() {
            if (readChildren() == null) {
                AtomicReferenceArray<Node> newChildren = new LinearChildren(INITIAL_LINEAR_NODE_SIZE);
                casChildren(null, newChildren);
            }
        }

        private boolean casChildren(AtomicReferenceArray<Node> expected, AtomicReferenceArray<Node> updated) {
            return childrenUpdater.compareAndSet(this, expected, updated);
        }

        private AtomicReferenceArray<Node> readChildren() {
            return children;
        }

        private static int hash(long key) {
            long v = key * 0x9e3775cd9e3775cdL;
            v = Long.reverseBytes(v);
            v = v * 0x9e3775cd9e3775cdL;
            return 0x7fff_ffff & (int) (v ^ (v >> 32));
        }

        public <C> void topDown(C currentContext, BiFunction<C, Long, C> createContext, BiConsumer<C, Long> consumeValue) {
            AtomicReferenceArray<Node> childrenSnapshot = readChildren();
            consumeValue.accept(currentContext, get());
            if (childrenSnapshot == null) {
                return;
            }
            for (int i = 0; i < childrenSnapshot.length(); i++) {
                Node child = read(childrenSnapshot, i);
                if (child != null && child != FROZEN_NODE) {
                    long key = child.getKey();
                    C extendedContext = createContext.apply(currentContext, key);
                    child.topDown(extendedContext, createContext, consumeValue);
                }
            }
        }

        @Override
        public String toString() {
            return "Node<" + value() + ">";
        }
    }

    private Node root;

    public LockFreePrefixTree() {
        this.root = new Node(0);
    }

    public Node root() {
        return root;
    }

    public <C> void topDown(C initialContext, BiFunction<C, Long, C> createContext, BiConsumer<C, Long> consumeValue) {
        root.topDown(initialContext, createContext, consumeValue);
    }
}
