/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.oracle.truffle.espresso.impl;

import java.util.concurrent.ConcurrentHashMap;

import com.oracle.truffle.espresso.classfile.ClassfileParser;
import com.oracle.truffle.espresso.classfile.ClassfileStream;
import com.oracle.truffle.espresso.descriptors.Types;
import com.oracle.truffle.espresso.impl.ByteString.Type;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.StaticObject;
import com.oracle.truffle.espresso.substitutions.Host;

/**
 * A {@link ClassRegistry} maps type names to resolved {@link Klass} instances. Each class
 * loader is associated with a {@link ClassRegistry} and vice versa.
 *
 * This class is analogous to the ClassLoaderData C++ class in HotSpot.
 */
public abstract class ClassRegistry {

    /**
     * The map from symbol to classes for the classes defined by the class loader associated with
     * this registry. Use of {@link ConcurrentHashMap} allows for atomic insertion while still
     * supporting fast, non-blocking lookup. There's no need for deletion as class unloading removes
     * a whole class registry and all its contained classes.
     */
    protected final ConcurrentHashMap<ByteString<Type>, Klass> classes = new ConcurrentHashMap<>();

    public abstract Klass loadKlass(ByteString<Type> type);

    public Klass findLoadedKlass(ByteString<Type> type) {
        if (Types.isArray(type)) {
            ByteString<Type> elemental = Types.getElementalType(type);
            Klass elementalKlass = findLoadedKlass(elemental);
            if (elementalKlass == null) {
                return null;
            }
            return elementalKlass.getArrayClass(Types.getArrayDimensions(type));
        }
        return classes.get(type);
    }

    public abstract @Host(ClassLoader.class) StaticObject getClassLoader();

    public ObjectKlass defineKlass(EspressoContext context, ByteString<Type> type, final byte[] bytes) {
        // EspressoError.guarantee(!classes.containsKey(type), "Class " + type + " already defined
        // in the BCL");

        ParserKlass parserKlass = ClassfileParser.parse(new ClassfileStream(bytes, null), type.toString(), null, context);

        ByteString<Type> superKlassType = parserKlass.getSuperKlass();

        // TODO(peterssen): Superclass must be a class, and non-final.
        ObjectKlass superKlass = superKlassType != null
                        ? (ObjectKlass) loadKlass(superKlassType) // Should only be an ObjectKlass,
                        // not primitives nor arrays.
                        : null;

        assert superKlass == null || !superKlass.isInterface();

        final ByteString<Type>[] superInterfacesTypes = parserKlass.getSuperInterfaces();

        LinkedKlass[] linkedInterfaces = new LinkedKlass[superInterfacesTypes.length];
        ObjectKlass[] superInterfaces = new ObjectKlass[superInterfacesTypes.length];

        // TODO(peterssen): Superinterfaces must be interfaces.
        for (int i = 0; i < superInterfacesTypes.length; ++i) {
            ObjectKlass interf = (ObjectKlass) loadKlass(superInterfacesTypes[i]);
            superInterfaces[i] = interf;
            linkedInterfaces[i] = interf.getLinkedKlass();
        }

        // FIXME(peterssen): Do NOT create a LinkedKlass every time, use a global cache.
        LinkedKlass linkedKlass = new LinkedKlass(parserKlass, superKlass.getLinkedKlass(), linkedInterfaces);

        ObjectKlass klass = new ObjectKlass(context, linkedKlass, superKlass, superInterfaces, getClassLoader(), null, null, null);
        classes.put(type, klass);
        return klass;
    }
}
