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
package com.oracle.truffle.espresso.jni;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.Message;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.Node.Child;
import com.oracle.truffle.espresso.Utils;
import com.oracle.truffle.espresso.descriptors.Signatures;
import com.oracle.truffle.espresso.descriptors.Types;
import com.oracle.truffle.espresso.impl.ByteString;
import com.oracle.truffle.espresso.impl.ByteString.Type;
import com.oracle.truffle.espresso.impl.ContextAccess;
import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.meta.JavaKind;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.meta.MetaUtil;
import com.oracle.truffle.espresso.nodes.EspressoRootNode;
import com.oracle.truffle.espresso.nodes.VmNativeNode;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.EspressoException;
import com.oracle.truffle.espresso.runtime.EspressoProperties;
import com.oracle.truffle.espresso.runtime.StaticObject;
import com.oracle.truffle.espresso.runtime.StaticObjectArray;
import com.oracle.truffle.espresso.runtime.StaticObjectClass;
import com.oracle.truffle.espresso.substitutions.Host;
import com.oracle.truffle.espresso.substitutions.Substitutions;
import com.oracle.truffle.espresso.vm.InterpreterToVM;
import com.oracle.truffle.nfi.types.NativeSimpleType;

public final class JniEnv extends NativeEnv implements ContextAccess {

    public static final int JNI_OK = 0; /* success */
    public static final int JNI_ERR = -1; /* unknown error */
    public static final int JNI_COMMIT = 1;
    public static final int JNI_ABORT = 2;

    public static final int JVM_INTERFACE_VERSION = 4;
    public static final int JNI_TRUE = 1;
    public static final int JNI_FALSE = 0;

    private final EspressoContext context;

    private long jniEnvPtr;

    // Load native library nespresso.dll (Windows) or libnespresso.so (Unixes) at runtime.
    private final TruffleObject nespressoLibrary;

    private final TruffleObject initializeNativeContext;
    private final TruffleObject disposeNativeContext;
    private final TruffleObject dupClosureRef;

    private final TruffleObject popBoolean;
    private final TruffleObject popByte;
    private final TruffleObject popChar;
    private final TruffleObject popShort;
    private final TruffleObject popInt;
    private final TruffleObject popFloat;
    private final TruffleObject popDouble;
    private final TruffleObject popLong;
    private final TruffleObject popObject;

    private static final Map<String, java.lang.reflect.Method> jniMethods = buildJniMethods();

    private final WeakHandles<Field> fieldIds = new WeakHandles<>();
    private final WeakHandles<Method> methodIds = new WeakHandles<>();

    // Prevent cleaner threads from collecting in-use native buffers.
    private final Map<Long, ByteBuffer> nativeBuffers = new ConcurrentHashMap<>();

    private final JniThreadLocalPendingException threadLocalPendingException = new JniThreadLocalPendingException();

    public JniThreadLocalPendingException getThreadLocalPendingException() {
        return threadLocalPendingException;
    }

    public Callback jniMethodWrapper(java.lang.reflect.Method m) {
        return new Callback(m.getParameterCount() + 1, new Callback.Function() {
            @Override
            public Object call(Object... args) {
                assert (long) args[0] == JniEnv.this.getNativePointer() : "Calling " + m + " from alien JniEnv";
                Object[] shiftedArgs = Arrays.copyOfRange(args, 1, args.length);

                Class<?>[] params = m.getParameterTypes();

                for (int i = 0; i < shiftedArgs.length; ++i) {
                    // FIXME(peterssen): Espresso should accept interop null objects, since it
                    // doesn't
                    // we must convert to Espresso null.
                    // FIXME(peterssen): Also, do use proper nodes.
                    if (shiftedArgs[i] instanceof TruffleObject) {
                        if (ForeignAccess.sendIsNull(Message.IS_NULL.createNode(), (TruffleObject) shiftedArgs[i])) {
                            shiftedArgs[i] = StaticObject.NULL;
                        }
                    } else {
                        // TruffleNFI pass booleans as byte, do the proper conversion.
                        if (params[i] == boolean.class) {
                            shiftedArgs[i] = ((byte) shiftedArgs[i]) != 0;
                        }
                    }
                }
                assert args.length - 1 == shiftedArgs.length;
                try {
                    // Substitute raw pointer by proper `this` reference.
                    // System.err.print("Call DEFINED method: " + m.getName() +
                    // Arrays.toString(shiftedArgs));
                    Object ret = m.invoke(JniEnv.this, shiftedArgs);

                    if (ret instanceof Boolean) {
                        return (boolean) ret ? (byte) 1 : (byte) 0;
                    }

                    if (ret == null && !m.getReturnType().isPrimitive()) {
                        throw EspressoError.shouldNotReachHere("Cannot return host null, only Espresso NULL");
                    }

                    if (ret == null && m.getReturnType() == void.class) {
                        // Cannot return host null to TruffleNFI.
                        ret = StaticObject.NULL;
                    }

                    // System.err.println(" -> " + ret);

                    return ret;
                } catch (InvocationTargetException e) {
                    Throwable targetEx = e.getTargetException();
                    if (targetEx instanceof EspressoException) {
                        JniEnv.this.getThreadLocalPendingException().set(((EspressoException) targetEx).getException());
                        return defaultValue(m.getReturnType());
                    } else if (targetEx instanceof RuntimeException) {
                        throw (RuntimeException) targetEx;
                    }
                    // FIXME(peterssen): Handle VME exceptions back to guest.
                    throw EspressoError.shouldNotReachHere(targetEx);
                } catch (IllegalAccessException e) {
                    throw EspressoError.shouldNotReachHere(e);
                }
            }
        });
    }

    public static String jniNativeSignature(java.lang.reflect.Method method) {
        StringBuilder sb = new StringBuilder("(");
        // Prepend JNIEnv* . The raw pointer will be substituted by the proper `this` reference.
        sb.append(NativeSimpleType.SINT64);
        for (Parameter param : method.getParameters()) {
            sb.append(", ");

            // Override NFI type.
            NFIType nfiType = param.getAnnotatedType().getAnnotation(NFIType.class);
            if (nfiType != null) {
                sb.append(NativeSimpleType.valueOf(nfiType.value().toUpperCase()));
            } else {
                sb.append(classToType(param.getType(), false));
            }
        }
        sb.append("): ").append(classToType(method.getReturnType(), true));
        return sb.toString();
    }

    public TruffleObject lookupJniImpl(String methodName) {
        java.lang.reflect.Method m = jniMethods.get(methodName);
        try {
            // Dummy placeholder for unimplemented/unknown methods.
            if (m == null) {
                // System.err.println("Fetching unknown/unimplemented JNI method: " + methodName);
                return (TruffleObject) ForeignAccess.sendExecute(Message.EXECUTE.createNode(), dupClosureRefAndCast("(pointer): void"),
                                new Callback(1, new Callback.Function() {
                                    @Override
                                    public Object call(Object... args) {
                                        System.err.println("Calling unimplemented JNI method: " + methodName);
                                        throw EspressoError.unimplemented("JNI method: " + methodName);
                                    }
                                }));
            }

            String signature = jniNativeSignature(m);
            Callback target = jniMethodWrapper(m);
            return (TruffleObject) ForeignAccess.sendExecute(Message.EXECUTE.createNode(), dupClosureRefAndCast(signature), target);
        } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean containsMethod(String methodName) {
        return jniMethods.containsKey(methodName);
    }

    private class VarArgsImpl implements VarArgs {

        @Child Node execute = Message.EXECUTE.createNode();
        private final long nativePointer;

        public VarArgsImpl(long nativePointer) {
            this.nativePointer = nativePointer;
        }

        @Override
        public boolean popBoolean() {
            try {
                return ((byte) ForeignAccess.sendExecute(execute, popBoolean, nativePointer)) != 0;
            } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public byte popByte() {
            try {
                return (byte) ForeignAccess.sendExecute(execute, popByte, nativePointer);
            } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public char popChar() {
            try {
                return (char) ForeignAccess.sendExecute(execute, popChar, nativePointer);
            } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public short popShort() {
            try {
                return (short) ForeignAccess.sendExecute(execute, popShort, nativePointer);
            } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public int popInt() {
            try {
                return (int) ForeignAccess.sendExecute(execute, popInt, nativePointer);
            } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public float popFloat() {
            try {
                return (float) ForeignAccess.sendExecute(execute, popFloat, nativePointer);
            } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public double popDouble() {
            try {
                return (Double) ForeignAccess.sendExecute(execute, popDouble, nativePointer);
            } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public long popLong() {
            try {
                return (long) ForeignAccess.sendExecute(execute, popLong, nativePointer);
            } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public StaticObject popObject() {
            try {
                return (StaticObject) ForeignAccess.sendExecute(execute, popObject, nativePointer);
            } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public Object[] popVarArgs(long varargsPtr, final ByteString<Type>[] signature) {
        VarArgs varargs = new VarArgsImpl(varargsPtr);
        int paramCount = Signatures.parameterCount(signature, false);
        Object[] args = new Object[paramCount];
        for (int i = 0; i < paramCount; ++i) {
            JavaKind kind = Signatures.parameterKind(signature, i);
            // @formatter:off
            // Checkstyle: stop
            switch (kind) {
                case Boolean : args[i] = varargs.popBoolean(); break;
                case Byte    : args[i] = varargs.popByte();    break;
                case Short   : args[i] = varargs.popShort();   break;
                case Char    : args[i] = varargs.popChar();    break;
                case Int     : args[i] = varargs.popInt();     break;
                case Float   : args[i] = varargs.popFloat();   break;
                case Long    : args[i] = varargs.popLong();    break;
                case Double  : args[i] = varargs.popDouble();  break;
                case Object  : args[i] = varargs.popObject();  break;
                default      : throw EspressoError.shouldNotReachHere("invalid parameter kind: " + kind);
            }
            // @formatter:on
            // Checkstyle: resume
        }
        return args;
    }

    public VarArgs varargs(long nativePointer) {
        return new VarArgsImpl(nativePointer);
    }

    private JniEnv(EspressoContext context) {
        try {
            EspressoProperties props = context.getVmProperties();
            this.context = context;
            nespressoLibrary = loadLibrary(props.getEspressoLibraryPath().split(File.pathSeparator), "nespresso");
            dupClosureRef = NativeLibrary.lookup(nespressoLibrary, "dupClosureRef");

            initializeNativeContext = NativeLibrary.lookupAndBind(nespressoLibrary,
                            "initializeNativeContext", "(env, (string): pointer): sint64");

            disposeNativeContext = NativeLibrary.lookupAndBind(nespressoLibrary, "disposeNativeContext",
                            "(env, sint64): void");

            // Vararg()ds
            popBoolean = NativeLibrary.lookupAndBind(nespressoLibrary, "pop_boolean", "(sint64): uint8");
            popByte = NativeLibrary.lookupAndBind(nespressoLibrary, "pop_byte", "(sint64): uint8");
            popChar = NativeLibrary.lookupAndBind(nespressoLibrary, "pop_char", "(sint64): uint16");
            popShort = NativeLibrary.lookupAndBind(nespressoLibrary, "pop_short", "(sint64): sint16");
            popInt = NativeLibrary.lookupAndBind(nespressoLibrary, "pop_int", "(sint64): sint32");
            popFloat = NativeLibrary.lookupAndBind(nespressoLibrary, "pop_float", "(sint64): float");
            popDouble = NativeLibrary.lookupAndBind(nespressoLibrary, "pop_double", "(sint64): double");
            popLong = NativeLibrary.lookupAndBind(nespressoLibrary, "pop_long", "(sint64): sint64");
            popObject = NativeLibrary.lookupAndBind(nespressoLibrary, "pop_object", "(sint64): object");

            Callback lookupJniImplCallback = Callback.wrapInstanceMethod(this, "lookupJniImpl", String.class);
            this.jniEnvPtr = (long) ForeignAccess.sendExecute(Message.EXECUTE.createNode(), initializeNativeContext, lookupJniImplCallback);
            assert this.jniEnvPtr != 0;
        } catch (UnsupportedMessageException | ArityException | UnknownIdentifierException | UnsupportedTypeException e) {
            throw EspressoError.shouldNotReachHere("Cannot initialize Espresso native interface");
        }
    }

    private static Map<String, java.lang.reflect.Method> buildJniMethods() {
        Map<String, java.lang.reflect.Method> map = new HashMap<>();
        java.lang.reflect.Method[] declaredMethods = JniEnv.class.getDeclaredMethods();
        for (java.lang.reflect.Method method : declaredMethods) {
            JniImpl jniImpl = method.getAnnotation(JniImpl.class);
            if (jniImpl != null) {
                assert !map.containsKey(method.getName()) : "JniImpl for " + method + " already exists";
                map.put(method.getName(), method);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    public TruffleObject dupClosureRefAndCast(String signature) {
        // TODO(peterssen): Cache binding per signature.
        return NativeLibrary.bind(dupClosureRef, "(env, " + signature + ")" + ": pointer");
    }

    public static JniEnv create(EspressoContext context) {
        return new JniEnv(context);
    }

    @Override
    public final EspressoContext getContext() {
        return context;
    }

    public long getNativePointer() {
        return jniEnvPtr;
    }

    public void dispose() {
        assert jniEnvPtr != 0L : "JNIEnv already disposed";
        try {
            ForeignAccess.sendExecute(Message.EXECUTE.createNode(), disposeNativeContext, jniEnvPtr);
            threadLocalPendingException.dispose();
            this.jniEnvPtr = 0L;
        } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
            throw EspressoError.shouldNotReachHere("Cannot initialize Espresso native interface");
        }
        assert jniEnvPtr == 0L;
    }

    /**
     * <h3>jint GetVersion(JNIEnv *env);</h3>
     *
     * Returns the version of the native method interface.
     *
     * @return the major version number in the higher 16 bits and the minor version number in the
     *         lower 16 bits.
     *
     *         <p>
     *         <b>Error codes</b>
     *         <ul>
     *         <li>#define JNI_EDETACHED (-2) // thread detached from the VM
     *         <li>#define JNI_EVERSION (-3) // JNI version error
     *         </ul>
     */
    @JniImpl
    public int GetVersion() {
        return JniVersion.JNI_VERSION_ESPRESSO;
    }

    /**
     * <h3>jsize GetArrayLength(JNIEnv *env, jarray array);</h3>
     *
     * Returns the number of elements in the array.
     *
     * @param array a Java array object.
     *
     * @return the length of the array.
     */
    @JniImpl
    public int GetArrayLength(@Host(Object.class) StaticObject array) {
        return getInterpreterToVM().arrayLength(array);
    }

    /**
     * <h3>jsize GetStringLength(JNIEnv *env, jstring string);</h3>
     *
     * Returns the length (the count of Unicode characters) of a Java string.
     *
     * @param string a Java string object.
     *
     * @return the length of the Java string.
     */
    @JniImpl
    public int GetStringLength(@Host(String.class) StaticObject string) {
        if (StaticObject.isNull(string)) {
            return 0;
        }
        return (int) getMeta().String_length.invokeDirect(string);
    }

    // region Get*ID

    /**
     * <h3>jfieldID GetFieldID(JNIEnv *env, jclass clazz, const char *name, const char *sig);</h3>
     *
     * Returns the field ID for an instance (nonstatic) field of a class. The field is specified by
     * its name and signature. The Get<type>Field and Set<type>Field families of accessor functions
     * use field IDs to retrieve object fields. GetFieldID() causes an uninitialized class to be
     * initialized. GetFieldID() cannot be used to obtain the length field of an array. Use
     * GetArrayLength() instead.
     *
     * @param clazz a Java class object.
     * @param name the field name in a 0-terminated modified UTF-8 string.
     * @param signature the field signature in a 0-terminated modified UTF-8 string.
     * @return a field ID, or NULL if the operation fails.
     *
     * @throws NoSuchFieldError: if the specified field cannot be found.
     * @throws ExceptionInInitializerError: if the class initializer fails due to an exception.
     * @throws OutOfMemoryError: if the system runs out of memory.
     */
    @JniImpl
    public long GetFieldID(@Host(Class.class) StaticObject clazz, String name, String signature) {
        Klass klass = ((StaticObjectClass) clazz).getMirror();
        klass.safeInitialize();
        ByteString<Type> fieldType = Types.fromJavaString(signature);
        Field field = klass.lookupField(ByteString.fromJavaString(name), fieldType);
        if (field == null) {
            throw getMeta().throwEx(NoSuchFieldError.class, name);
        }
        assert !field.isStatic();
        assert field.getType().equals(fieldType);
        return fieldIds.handlify(field);
    }

    /**
     * <h3>jfieldID GetStaticFieldID(JNIEnv *env, jclass clazz, const char *name, const char *sig);
     * </h3>
     *
     * Returns the field ID for a static field of a class. The field is specified by its name and
     * signature. The GetStatic<type>Field and SetStatic<type>Field families of accessor functions
     * use field IDs to retrieve static fields.
     *
     * GetStaticFieldID() causes an uninitialized class to be initialized.
     * 
     * @param clazz a Java class object.
     * @param name the static field name in a 0-terminated modified UTF-8 string.
     * @param signature the field signature in a 0-terminated modified UTF-8 string.
     *
     * @return a field ID, or NULL if the specified static field cannot be found.
     * @throws NoSuchFieldError if the specified static field cannot be found.
     * @throws ExceptionInInitializerError if the class initializer fails due to an exception.
     * @throws OutOfMemoryError if the system runs out of memory.
     */
    @JniImpl
    public long GetStaticFieldID(@Host(Class.class) StaticObject clazz, String name, String signature) {
        Klass klass = ((StaticObjectClass) clazz).getMirror();
        klass.safeInitialize();
        ByteString<Type> fieldType = Types.fromJavaString(signature);
        Field field = klass.lookupDeclaredField(ByteString.fromJavaString(name), fieldType);
        if (field == null) {
            throw getMeta().throwEx(NoSuchFieldError.class, name);
        }
        assert field.isStatic();
        assert field.getType().equals(fieldType);
        return fieldIds.handlify(field);
    }

    /**
     * <h3>jmethodID GetMethodID(JNIEnv *env, jclass clazz, const char *name, const char *sig);</h3>
     *
     * Returns the method ID for an instance (nonstatic) method of a class or interface. The method
     * may be defined in one of the clazz’s superclasses and inherited by clazz. The method is
     * determined by its name and signature.
     *
     * GetMethodID() causes an uninitialized class to be initialized.
     *
     * To obtain the method ID of a constructor, supply <init> as the method name and void (V) as
     * the return type.
     *
     * @param clazz a Java class object.
     * @param name the method name in a 0-terminated modified UTF-8 string.
     * @param signature the method signature in 0-terminated modified UTF-8 string.
     *
     * @return a method ID, or NULL if the specified method cannot be found.
     *
     * @throws NoSuchMethodError if the specified method cannot be found.
     * @throws ExceptionInInitializerError if the class initializer fails due to an exception.
     * @throws OutOfMemoryError if the system runs out of memory.
     *
     */
    @JniImpl
    public long GetMethodID(@Host(Class.class) StaticObject clazz, String name, String signature) {
        Klass klass = ((StaticObjectClass) clazz).getMirror();
        klass.safeInitialize();
        Method method = klass.lookupMethod(ByteString.fromJavaString(name), Signatures.fromJavaString(signature));
        if (method == null) {
            throw getMeta().throwEx(NoSuchMethodError.class, name + signature);
        }
        assert !method.isStatic();
        return methodIds.handlify(method);
    }

    /**
     * <h3>jmethodID GetStaticMethodID(JNIEnv *env, jclass clazz, const char *name, const char
     * *sig);</h3>
     *
     * Returns the method ID for a static method of a class. The method is specified by its name and
     * signature.
     *
     * GetStaticMethodID() causes an uninitialized class to be initialized.
     *
     * @param clazz a Java class object.
     * @param name the static method name in a 0-terminated modified UTF-8 string.
     * @param signature the method signature in a 0-terminated modified UTF-8 string.
     *
     * @return a method ID, or NULL if the operation fails.
     *
     * @throws NoSuchMethodError if the specified static method cannot be found. *
     * @throws ExceptionInInitializerError if the class initializer fails due to an exception.
     * @throws OutOfMemoryError if the system runs out of memory.
     */
    @JniImpl
    public long GetStaticMethodID(@Host(Class.class) StaticObject clazz, String name, String signature) {
        Klass klass = ((StaticObjectClass) clazz).getMirror();
        klass.safeInitialize();
        Method method = klass.lookupDeclaredMethod(ByteString.fromJavaString(name), Signatures.fromJavaString(signature));
        if (method == null) {
            throw getMeta().throwEx(NoSuchMethodError.class, name + signature);
        }
        assert method.isStatic();
        return methodIds.handlify(method);
    }

    // endregion Get*ID

    // region Get*Field

    @JniImpl
    public Object GetObjectField(StaticObject obj, long fieldHandle) {
        Field field = fieldIds.getObject(fieldHandle);
        return field.get(obj);
    }

    @JniImpl
    public boolean GetBooleanField(StaticObject object, long fieldHandle) {
        Field field = fieldIds.getObject(fieldHandle);
        return (boolean) field.get(object);
    }

    @JniImpl
    public byte GetByteField(StaticObject object, long fieldHandle) {
        Field field = fieldIds.getObject(fieldHandle);
        return (byte) field.get(object);
    }

    @JniImpl
    public char GetCharField(StaticObject object, long fieldHandle) {
        Field field = fieldIds.getObject(fieldHandle);
        return (char) field.get(object);
    }

    @JniImpl
    public short GetShortField(StaticObject object, long fieldHandle) {
        Field field = fieldIds.getObject(fieldHandle);
        return (short) field.get(object);
    }

    @JniImpl
    public int GetIntField(StaticObject object, long fieldHandle) {
        Field field = fieldIds.getObject(fieldHandle);
        return (int) field.get(object);
    }

    @JniImpl
    public long GetLongField(StaticObject object, long fieldHandle) {
        Field field = fieldIds.getObject(fieldHandle);
        return (long) field.get(object);
    }

    @JniImpl
    public float GetFloatField(StaticObject object, long fieldHandle) {
        Field field = fieldIds.getObject(fieldHandle);
        return (float) field.get(object);
    }

    @JniImpl
    public double GetDoubleField(StaticObject object, long fieldHandle) {
        Field field = fieldIds.getObject(fieldHandle);
        return (double) field.get(object);
    }

    // endregion Get*Field

    // region Set*Field

    @JniImpl
    public void SetObjectField(StaticObject obj, long fieldHandle, Object val) {
        Field field = fieldIds.getObject(fieldHandle);
        field.set(obj, val);
    }

    @JniImpl
    public void SetBooleanField(StaticObject obj, long fieldHandle, boolean val) {
        Field field = fieldIds.getObject(fieldHandle);
        field.set(obj, val);
    }

    @JniImpl
    public void SetByteField(StaticObject obj, long fieldHandle, byte val) {
        Field field = fieldIds.getObject(fieldHandle);
        field.set(obj, val);
    }

    @JniImpl
    public void SetCharField(StaticObject obj, long fieldHandle, char val) {
        Field field = fieldIds.getObject(fieldHandle);
        field.set(obj, val);
    }

    @JniImpl
    public void SetShortField(StaticObject obj, long fieldHandle, short val) {
        Field field = fieldIds.getObject(fieldHandle);
        field.set(obj, val);
    }

    @JniImpl
    public void SetIntField(StaticObject obj, long fieldHandle, int val) {
        Field field = fieldIds.getObject(fieldHandle);
        field.set(obj, val);
    }

    @JniImpl
    public void SetLongField(StaticObject obj, long fieldHandle, long val) {
        Field field = fieldIds.getObject(fieldHandle);
        field.set(obj, val);
    }

    @JniImpl
    public void SetFloatField(StaticObject obj, long fieldHandle, float val) {
        Field field = fieldIds.getObject(fieldHandle);
        field.set(obj, val);
    }

    @JniImpl
    public void SetDoubleField(StaticObject obj, long fieldHandle, double val) {
        Field field = fieldIds.getObject(fieldHandle);
        field.set(obj, val);
    }

    // endregion Set*Field

    // region Call*Method

    @JniImpl
    public Object CallObjectMethodVarargs(@Host(Object.class) StaticObject receiver, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        // FIXME(peterssen): This is virtual dispatch. Re-resolve the method.
        return method.invokeDirect(receiver, popVarArgs(varargsPtr, method.getParsedSignature()));
    }

    @SuppressWarnings("unused")
    @JniImpl
    public boolean CallBooleanMethodVarargs(@Host(Object.class) StaticObject receiver, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        new RuntimeException().printStackTrace();
        throw EspressoError.unimplemented();
    }

    @SuppressWarnings("unused")
    @JniImpl
    public char CallCharMethodVarargs(@Host(Object.class) StaticObject receiver, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        new RuntimeException().printStackTrace();
        throw EspressoError.unimplemented();
    }

    @SuppressWarnings("unused")
    @JniImpl
    public byte CallByteMethodVarargs(@Host(Object.class) StaticObject receiver, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        new RuntimeException().printStackTrace();
        throw EspressoError.unimplemented();
    }

    @SuppressWarnings("unused")
    @JniImpl
    public short CallShortMethodVarargs(@Host(Object.class) StaticObject receiver, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        new RuntimeException().printStackTrace();
        throw EspressoError.unimplemented();
    }

    @SuppressWarnings("unused")
    @JniImpl
    public int CallIntMethodVarargs(@Host(Object.class) StaticObject receiver, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        new RuntimeException().printStackTrace();
        throw EspressoError.unimplemented();
    }

    @SuppressWarnings("unused")
    @JniImpl
    public float CallFloatMethodVarargs(@Host(Object.class) StaticObject receiver, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        new RuntimeException().printStackTrace();
        throw EspressoError.unimplemented();
    }

    @SuppressWarnings("unused")
    @JniImpl
    public double CallDoubleMethodVarargs(@Host(Object.class) StaticObject receiver, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        new RuntimeException().printStackTrace();
        throw EspressoError.unimplemented();
    }

    @SuppressWarnings("unused")
    @JniImpl
    public long CallLongMethodVarargs(@Host(Object.class) StaticObject receiver, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        new RuntimeException().printStackTrace();
        throw EspressoError.unimplemented();
    }

    @SuppressWarnings("unused")
    @JniImpl
    public void CallVoidMethodVarargs(@Host(Object.class) StaticObject receiver, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        new RuntimeException().printStackTrace();
        throw EspressoError.unimplemented();
    }

    // endregion Call*Method

    // region CallNonvirtual*Method

    @JniImpl
    public @Host(Object.class) StaticObject CallNonvirtualObjectMethodVarargs(@Host(Object.class) StaticObject receiver, @Host(Class.class) StaticObject clazz, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        assert !method.isStatic();
        assert (((StaticObjectClass) clazz).getMirror()) == method.getDeclaringKlass();
        return (StaticObject) method.invokeDirect(receiver, popVarArgs(varargsPtr, method.getParsedSignature()));
    }

    @JniImpl
    public boolean CallNonvirtualBooleanMethodVarargs(@Host(Object.class) StaticObject receiver, @Host(Class.class) StaticObject clazz, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        assert !method.isStatic();
        assert (((StaticObjectClass) clazz).getMirror()) == method.getDeclaringKlass();
        return (boolean) method.invokeDirect(receiver, popVarArgs(varargsPtr, method.getParsedSignature()));
    }

    @JniImpl
    public char CallNonvirtualCharMethodVarargs(@Host(Object.class) StaticObject receiver, @Host(Class.class) StaticObject clazz, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        assert !method.isStatic();
        assert (((StaticObjectClass) clazz).getMirror()) == method.getDeclaringKlass();
        return (char) method.invokeDirect(receiver, popVarArgs(varargsPtr, method.getParsedSignature()));
    }

    @JniImpl
    public byte CallNonvirtualByteMethodVarargs(@Host(Object.class) StaticObject receiver, @Host(Class.class) StaticObject clazz, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        assert !method.isStatic();
        assert (((StaticObjectClass) clazz).getMirror()) == method.getDeclaringKlass();
        return (byte) method.invokeDirect(receiver, popVarArgs(varargsPtr, method.getParsedSignature()));
    }

    @JniImpl
    public short CallNonvirtualShortMethodVarargs(@Host(Object.class) StaticObject receiver, @Host(Class.class) StaticObject clazz, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        assert !method.isStatic();
        assert (((StaticObjectClass) clazz).getMirror()) == method.getDeclaringKlass();
        return (short) method.invokeDirect(receiver, popVarArgs(varargsPtr, method.getParsedSignature()));
    }

    @JniImpl
    public int CallNonvirtualIntMethodVarargs(@Host(Object.class) StaticObject receiver, @Host(Class.class) StaticObject clazz, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        assert !method.isStatic();
        assert (((StaticObjectClass) clazz).getMirror()) == method.getDeclaringKlass();
        return (int) method.invokeDirect(receiver, popVarArgs(varargsPtr, method.getParsedSignature()));
    }

    @JniImpl
    public float CallNonvirtualFloatMethodVarargs(@Host(Object.class) StaticObject receiver, @Host(Class.class) StaticObject clazz, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        assert !method.isStatic();
        assert (((StaticObjectClass) clazz).getMirror()) == method.getDeclaringKlass();
        return (float) method.invokeDirect(receiver, popVarArgs(varargsPtr, method.getParsedSignature()));
    }

    @JniImpl
    public double CallNonvirtualDoubleMethodVarargs(@Host(Object.class) StaticObject receiver, @Host(Class.class) StaticObject clazz, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        assert !method.isStatic();
        assert (((StaticObjectClass) clazz).getMirror()) == method.getDeclaringKlass();
        return (double) method.invokeDirect(receiver, popVarArgs(varargsPtr, method.getParsedSignature()));
    }

    @JniImpl
    public long CallNonvirtualLongMethodVarargs(@Host(Object.class) StaticObject receiver, @Host(Class.class) StaticObject clazz, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        assert !method.isStatic();
        assert (((StaticObjectClass) clazz).getMirror()) == method.getDeclaringKlass();
        return (long) method.invokeDirect(receiver, popVarArgs(varargsPtr, method.getParsedSignature()));
    }

    @JniImpl
    public void CallNonvirtualVoidMethodVarargs(@Host(Object.class) StaticObject receiver, @Host(Class.class) StaticObject clazz, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        assert !method.isStatic();
        assert (((StaticObjectClass) clazz).getMirror()) == method.getDeclaringKlass();
        method.invokeDirect(receiver, popVarArgs(varargsPtr, method.getParsedSignature()));
    }

    @JniImpl
    public @Host(Object.class) StaticObject CallNonvirtualtualObjectMethodVarargs(@Host(Object.class) StaticObject receiver, @Host(Class.class) StaticObject clazz, long methodHandle,
                    long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        assert !method.isStatic();
        assert (((StaticObjectClass) clazz).getMirror()) == method.getDeclaringKlass();
        return (StaticObject) method.invokeDirect(receiver, popVarArgs(varargsPtr, method.getParsedSignature()));
    }

    // endregion CallNonvirtual*Method

    // region CallStatic*Method

    @JniImpl
    public Object CallStaticObjectMethodVarargs(@Host(Class.class) StaticObject clazz, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        assert method.isStatic();
        assert (((StaticObjectClass) clazz).getMirror()) == method.getDeclaringKlass();
        return method.invokeDirect(null, popVarArgs(varargsPtr, method.getParsedSignature()));
    }

    @JniImpl
    public boolean CallStaticBooleanMethodVarargs(@Host(Class.class) StaticObject clazz, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        assert method.isStatic();
        assert (((StaticObjectClass) clazz).getMirror()) == method.getDeclaringKlass();
        return (boolean) method.invokeDirect(null, popVarArgs(varargsPtr, method.getParsedSignature()));
    }

    @JniImpl
    public char CallStaticCharMethodVarargs(@Host(Class.class) StaticObject clazz, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        assert method.isStatic();
        assert (((StaticObjectClass) clazz).getMirror()) == method.getDeclaringKlass();
        return (char) method.invokeDirect(null, popVarArgs(varargsPtr, method.getParsedSignature()));
    }

    @JniImpl
    public byte CallStaticByteMethodVarargs(@Host(Class.class) StaticObject clazz, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        assert method.isStatic();
        assert (((StaticObjectClass) clazz).getMirror()) == method.getDeclaringKlass();
        return (byte) method.invokeDirect(null, popVarArgs(varargsPtr, method.getParsedSignature()));
    }

    @JniImpl
    public short CallStaticShortMethodVarargs(@Host(Class.class) StaticObject clazz, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        assert method.isStatic();
        assert (((StaticObjectClass) clazz).getMirror()) == method.getDeclaringKlass();
        return (short) method.invokeDirect(null, popVarArgs(varargsPtr, method.getParsedSignature()));
    }

    @JniImpl
    public int CallStaticIntMethodVarargs(@Host(Class.class) StaticObject clazz, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        assert method.isStatic();
        assert (((StaticObjectClass) clazz).getMirror()) == method.getDeclaringKlass();
        return (int) method.invokeDirect(null, popVarArgs(varargsPtr, method.getParsedSignature()));
    }

    @JniImpl
    public float CallStaticFloatMethodVarargs(@Host(Class.class) StaticObject clazz, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        assert method.isStatic();
        assert (((StaticObjectClass) clazz).getMirror()) == method.getDeclaringKlass();
        return (float) method.invokeDirect(null, null, popVarArgs(varargsPtr, method.getParsedSignature()));
    }

    @JniImpl
    public double CallStaticDoubleMethodVarargs(@Host(Class.class) StaticObject clazz, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        assert method.isStatic();
        assert (((StaticObjectClass) clazz).getMirror()) == method.getDeclaringKlass();
        return (double) method.invokeDirect(null, popVarArgs(varargsPtr, method.getParsedSignature()));
    }

    @JniImpl
    public long CallStaticLongMethodVarargs(@Host(Class.class) StaticObject clazz, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        assert method.isStatic();
        assert (((StaticObjectClass) clazz).getMirror()) == method.getDeclaringKlass();
        return (long) method.invokeDirect(null, popVarArgs(varargsPtr, method.getParsedSignature()));
    }

    @JniImpl
    public void CallStaticVoidMethodVarargs(@Host(Class.class) StaticObject clazz, long methodHandle, long varargsPtr) {
        Method method = methodIds.getObject(methodHandle);
        assert method.isStatic();
        assert (((StaticObjectClass) clazz).getMirror()) == method.getDeclaringKlass();
        method.invokeDirect(null, popVarArgs(varargsPtr, method.getParsedSignature()));
    }

    // endregion CallStatic*Method

    // region Get*ArrayRegion

    @JniImpl
    public void GetBooleanArrayRegion(@Host(boolean[].class) StaticObject array, int start, int len, long bufPtr) {
        ByteBuffer buf = directByteBuffer(bufPtr, len, JavaKind.Byte);
        boolean[] booleans = ((StaticObjectArray) array).unwrap();
        for (int i = 0; i < len; ++i) {
            buf.put(booleans[start + i] ? (byte) 1 : (byte) 0);
        }
    }

    @JniImpl
    public void GetCharArrayRegion(@Host(char[].class) StaticObject array, int start, int len, long bufPtr) {
        CharBuffer buf = directByteBuffer(bufPtr, len, JavaKind.Char).asCharBuffer();
        buf.put(((StaticObjectArray) array).<char[]> unwrap(), start, len);
    }

    @JniImpl
    public void GetByteArrayRegion(@Host(byte[].class) StaticObject array, int start, int len, long bufPtr) {
        ByteBuffer buf = directByteBuffer(bufPtr, len, JavaKind.Byte);
        buf.put(((StaticObjectArray) array).unwrap(), start, len);
    }

    @JniImpl
    public void GetShortArrayRegion(@Host(short[].class) StaticObject array, int start, int len, long bufPtr) {
        ShortBuffer buf = directByteBuffer(bufPtr, len, JavaKind.Short).asShortBuffer();
        buf.put(((StaticObjectArray) array).unwrap(), start, len);
    }

    @JniImpl
    public void GetIntArrayRegion(@Host(int[].class) StaticObject array, int start, int len, long bufPtr) {
        IntBuffer buf = directByteBuffer(bufPtr, len, JavaKind.Int).asIntBuffer();
        buf.put(((StaticObjectArray) array).unwrap(), start, len);
    }

    @JniImpl
    public void GetFloatArrayRegion(@Host(float[].class) StaticObject array, int start, int len, long bufPtr) {
        FloatBuffer buf = directByteBuffer(bufPtr, len, JavaKind.Float).asFloatBuffer();
        buf.put(((StaticObjectArray) array).unwrap(), start, len);
    }

    @JniImpl
    public void GetDoubleArrayRegion(@Host(double[].class) StaticObject array, int start, int len, long bufPtr) {
        DoubleBuffer buf = directByteBuffer(bufPtr, len, JavaKind.Double).asDoubleBuffer();
        buf.put(((StaticObjectArray) array).unwrap(), start, len);
    }

    @JniImpl
    public void GetLongArrayRegion(@Host(long[].class) StaticObject array, int start, int len, long bufPtr) {
        LongBuffer buf = directByteBuffer(bufPtr, len, JavaKind.Long).asLongBuffer();
        buf.put(((StaticObjectArray) array).unwrap(), start, len);
    }

    // endregion Get*ArrayRegion

    // region Set*ArrayRegion

    @JniImpl
    public void SetBooleanArrayRegion(@Host(boolean[].class) StaticObject array, int start, int len, long bufPtr) {
        ByteBuffer buf = directByteBuffer(bufPtr, len, JavaKind.Byte);
        boolean[] booleans = ((StaticObjectArray) array).unwrap();
        for (int i = 0; i < len; ++i) {
            booleans[start + i] = buf.get() != 0;
        }
    }

    @JniImpl
    public void SetCharArrayRegion(@Host(char[].class) StaticObject array, int start, int len, long bufPtr) {
        CharBuffer buf = directByteBuffer(bufPtr, len, JavaKind.Char).asCharBuffer();
        buf.get(((StaticObjectArray) array).<char[]> unwrap(), start, len);
    }

    @JniImpl
    public void SetByteArrayRegion(@Host(byte[].class) StaticObject array, int start, int len, long bufPtr) {
        ByteBuffer buf = directByteBuffer(bufPtr, len, JavaKind.Byte);
        buf.get(((StaticObjectArray) array).unwrap(), start, len);
    }

    @JniImpl
    public void SetShortArrayRegion(@Host(short[].class) StaticObject array, int start, int len, long bufPtr) {
        ShortBuffer buf = directByteBuffer(bufPtr, len, JavaKind.Short).asShortBuffer();
        buf.get(((StaticObjectArray) array).unwrap(), start, len);
    }

    @JniImpl
    public void SetIntArrayRegion(@Host(int[].class) StaticObject array, int start, int len, long bufPtr) {
        IntBuffer buf = directByteBuffer(bufPtr, len, JavaKind.Int).asIntBuffer();
        buf.get(((StaticObjectArray) array).unwrap(), start, len);
    }

    @JniImpl
    public void SetFloatArrayRegion(@Host(float[].class) StaticObject array, int start, int len, long bufPtr) {
        FloatBuffer buf = directByteBuffer(bufPtr, len, JavaKind.Float).asFloatBuffer();
        buf.get(((StaticObjectArray) array).unwrap(), start, len);
    }

    @JniImpl
    public void SetDoubleArrayRegion(@Host(double[].class) StaticObject array, int start, int len, long bufPtr) {
        DoubleBuffer buf = directByteBuffer(bufPtr, len, JavaKind.Double).asDoubleBuffer();
        buf.get(((StaticObjectArray) array).unwrap(), start, len);
    }

    @JniImpl
    public void SetLongArrayRegion(@Host(long[].class) StaticObject array, int start, int len, long bufPtr) {
        LongBuffer buf = directByteBuffer(bufPtr, len, JavaKind.Long).asLongBuffer();
        buf.get(((StaticObjectArray) array).unwrap(), start, len);
    }

    // endregion Set*ArrayRegion

    @JniImpl
    public long GetPrimitiveArrayCritical(StaticObject object, long isCopyPtr) {
        if (isCopyPtr != 0L) {
            ByteBuffer isCopyBuf = directByteBuffer(isCopyPtr, 1);
            isCopyBuf.put((byte) 1); // Always copy since pinning is not supported.
        }
        StaticObjectArray array = (StaticObjectArray) object;
        StaticObjectClass clazz = (StaticObjectClass) GetObjectClass(array);
        JavaKind componentKind = clazz.getMirror().getComponentType().getJavaKind();
        assert componentKind.isPrimitive();
        int length = GetArrayLength(array);

        ByteBuffer region = allocateDirect(length, componentKind);
        long address = byteBufferAddress(region);
        // @formatter:off
        // Checkstyle: stop
        switch (componentKind) {
            case Boolean:
                GetBooleanArrayRegion(array, 0, length, address);
                break;
            case Byte:
                GetByteArrayRegion(array, 0, length, address);
                break;
            case Short:
                GetShortArrayRegion(array, 0, length, address);
                break;
            case Char:
                GetCharArrayRegion(array, 0, length, address);
                break;
            case Int:
                GetIntArrayRegion(array, 0, length, address);
                break;
            case Float:
                GetFloatArrayRegion(array, 0, length, address);
                break;
            case Long:
                GetLongArrayRegion(array, 0, length, address);
                break;
            case Double:
                GetDoubleArrayRegion(array, 0, length, address);
                break;
            case Object:  // fall through
            case Void:    // fall through
            case Illegal:
                throw EspressoError.shouldNotReachHere();
        }
        // @formatter:on
        // Checkstyle: resume

        return address;
    }

    @JniImpl
    public void ReleasePrimitiveArrayCritical(StaticObject object, long carrayPtr, int mode) {
        if (mode == 0 || mode == JNI_COMMIT) { // Update array contents.
            StaticObjectArray array = (StaticObjectArray) object;
            StaticObjectClass clazz = (StaticObjectClass) GetObjectClass(array);
            JavaKind componentKind = clazz.getMirror().getComponentType().getJavaKind();
            assert componentKind.isPrimitive();
            int length = GetArrayLength(array);
            // @formatter:off
            // Checkstyle: stop
            switch (componentKind) {
                case Boolean : SetBooleanArrayRegion(array, 0, length, carrayPtr); break;
                case Byte    : SetByteArrayRegion(array, 0, length, carrayPtr);    break;
                case Short   : SetShortArrayRegion(array, 0, length, carrayPtr);   break;
                case Char    : SetCharArrayRegion(array, 0, length, carrayPtr);    break;
                case Int     : SetIntArrayRegion(array, 0, length, carrayPtr);     break;
                case Float   : SetFloatArrayRegion(array, 0, length, carrayPtr);   break;
                case Long    : SetLongArrayRegion(array, 0, length, carrayPtr);    break;
                case Double  : SetDoubleArrayRegion(array, 0, length, carrayPtr);  break;
                default      : throw EspressoError.shouldNotReachHere();
            }
            // @formatter:on
            // Checkstyle: resume
        }
        if (mode == 0 || mode == JNI_ABORT) { // Dispose copy.
            assert nativeBuffers.containsKey(carrayPtr);
            nativeBuffers.remove(carrayPtr);
        }
    }

    // region New*Array

    @JniImpl
    Object NewBooleanArray(int len) {
        return InterpreterToVM.allocatePrimitiveArray((byte) JavaKind.Boolean.getBasicType(), len);
    }

    @JniImpl
    Object NewByteArray(int len) {
        return InterpreterToVM.allocatePrimitiveArray((byte) JavaKind.Byte.getBasicType(), len);
    }

    @JniImpl
    Object NewCharArray(int len) {
        return InterpreterToVM.allocatePrimitiveArray((byte) JavaKind.Char.getBasicType(), len);
    }

    @JniImpl
    Object NewShortArray(int len) {
        return InterpreterToVM.allocatePrimitiveArray((byte) JavaKind.Short.getBasicType(), len);
    }

    @JniImpl
    Object NewIntArray(int len) {
        return InterpreterToVM.allocatePrimitiveArray((byte) JavaKind.Int.getBasicType(), len);
    }

    @JniImpl
    Object NewLongArray(int len) {
        return InterpreterToVM.allocatePrimitiveArray((byte) JavaKind.Long.getBasicType(), len);
    }

    @JniImpl
    Object NewFloatArray(int len) {
        return InterpreterToVM.allocatePrimitiveArray((byte) JavaKind.Float.getBasicType(), len);
    }

    @JniImpl
    Object NewDoubleArray(int len) {
        return InterpreterToVM.allocatePrimitiveArray((byte) JavaKind.Double.getBasicType(), len);
    }

    // endregion New*Array

    @JniImpl
    public void SetStaticObjectField(StaticObjectClass clazz, long fieldHandle, Object value) {
        Field field = fieldIds.getObject(fieldHandle);
        assert field.isStatic();
        field.set(clazz.getMirror().tryInitializeAndGetStatics(), value);
    }

    @JniImpl
    public StaticObject GetObjectClass(StaticObject self) {
        return self.getKlass().mirror();
    }

    @JniImpl
    public Object NewObjectVarargs(@Host(Class.class) StaticObject clazz, long methodHandle, long varargsPtr) {
        Klass klass = ((StaticObjectClass) clazz).getMirror();
        Method method = methodIds.getObject(methodHandle);
        assert method.isConstructor();
        StaticObject instance = klass.allocateInstance();
        method.invokeDirect(instance, popVarArgs(varargsPtr, method.getParsedSignature()));
        return instance;
    }

    @JniImpl
    public StaticObject NewStringUTF(String hostString) {
        // FIXME(peterssen): This relies on TruffleNFI implicit char* -> String conversion that
        // uses host NewStringUTF.
        return getMeta().toGuest(hostString);
    }

    /**
     * <h3>jclass FindClass(JNIEnv *env, const char *name);</h3>
     *
     * <p>
     * FindClass locates the class loader associated with the current native method; that is, the
     * class loader of the class that declared the native method. If the native method belongs to a
     * system class, no class loader will be involved. Otherwise, the proper class loader will be
     * invoked to load and link the named class. Since Java 2 SDK release 1.2, when FindClass is
     * called through the Invocation Interface, there is no current native method or its associated
     * class loader. In that case, the result of {@link ClassLoader#getSystemClassLoader} is used.
     * This is the class loader the virtual machine creates for applications, and is able to locate
     * classes listed in the java.class.path property. The name argument is a fully-qualified class
     * name or an array type signature .
     * <p>
     * For example, the fully-qualified class name for the {@code java.lang.String} class is:
     *
     * <pre>
     * "java/lang/String"}
     * </pre>
     *
     * <p>
     * The array type signature of the array class {@code java.lang.Object[]} is:
     *
     * <pre>
     * "[Ljava/lang/Object;"
     * </pre>
     *
     * @param name a fully-qualified class name (that is, a package name, delimited by "/", followed
     *            by the class name). If the name begins with "[" (the array signature character),
     *            it returns an array class. The string is encoded in modified UTF-8.
     *
     * @return Returns a class object from a fully-qualified name, or NULL if the class cannot be
     *         found.
     *
     * @throws ClassFormatError if the class data does not specify a valid class.
     * @throws ClassCircularityError if a class or interface would be its own superclass or
     *             superinterface.
     * @throws NoClassDefFoundError if no definition for a requested class or interface can be
     *             found.
     * @throws OutOfMemoryError if the system runs out of memory.
     */
    @JniImpl
    public StaticObject FindClass(String name) {
        StaticObject internalName = getMeta().toGuest(MetaUtil.toInternalName(name));
        assert getMeta().Class_forName_String.isStatic();
        return (StaticObject) getMeta().Class_forName_String.invokeDirect(null, internalName);
    }

    /**
     * <h3>jobject NewLocalRef(JNIEnv *env, jobject ref);</h3>
     *
     * Creates a new local reference that refers to the same object as ref. The given ref may be a
     * global or local reference. Returns NULL if ref refers to null.
     */
    @JniImpl
    public Object NewLocalRef(Object ref) {
        // Local ref is allocated by host JNI on return.
        return ref;
    }

    /**
     * <h3>jboolean ExceptionCheck(JNIEnv *env);</h3>
     * 
     * A convenience function to check for pending exceptions without creating a local reference to
     * the exception object.
     *
     * @return JNI_TRUE when there is a pending exception; otherwise, returns JNI_FALSE.
     */
    @JniImpl
    public boolean ExceptionCheck() {
        StaticObject ex = threadLocalPendingException.get();
        assert ex != StaticObject.NULL;
        return ex != null;
    }

    /**
     * <h3>void ExceptionClear(JNIEnv *env);</h3>
     *
     * Clears any exception that is currently being thrown. If no exception is currently being
     * thrown, this routine has no effect.
     */
    @JniImpl
    public void ExceptionClear() {
        getThreadLocalPendingException().clear();
    }

    /**
     * <h3>const jchar * GetStringCritical(JNIEnv *env, jstring string, jboolean *isCopy);</h3>
     *
     * The semantics of these two functions are similar to the existing Get/ReleaseStringChars
     * functions. If possible, the VM returns a pointer to string elements; otherwise, a copy is
     * made.
     *
     * <p>
     * However, there are significant restrictions on how these functions can be used. In a code
     * segment enclosed by Get/ReleaseStringCritical calls, the native code must not issue arbitrary
     * JNI calls, or cause the current thread to block.
     *
     * <p>
     * The restrictions on Get/ReleaseStringCritical are similar to those on
     * Get/ReleasePrimitiveArrayCritical.
     */
    @JniImpl
    public long GetStringCritical(@Host(String.class) StaticObject str, long isCopyPtr) {
        if (isCopyPtr != 0L) {
            ByteBuffer isCopyBuf = directByteBuffer(isCopyPtr, 1);
            isCopyBuf.put((byte) 1); // always copy since pinning is not supported
        }
        final StaticObjectArray stringChars = (StaticObjectArray) getMeta().String_value.get(str);
        int len = stringChars.length();
        ByteBuffer criticalRegion = allocateDirect(len, JavaKind.Char); // direct byte buffer
        // (non-relocatable)
        long address = byteBufferAddress(criticalRegion);
        GetCharArrayRegion(stringChars, 0, len, address);
        return address;
    }

    private ByteBuffer allocateDirect(int capacity, JavaKind kind) {
        return allocateDirect(Math.multiplyExact(capacity, kind.getByteCount()));
    }

    private ByteBuffer allocateDirect(int capacity) {
        ByteBuffer bb = ByteBuffer.allocateDirect(capacity) //
                        .order(ByteOrder.nativeOrder());
        long address = byteBufferAddress(bb);
        nativeBuffers.put(address, bb);
        return bb;
    }

    @JniImpl
    public long GetStringUTFChars(@Host(String.class) StaticObject str, long isCopyPtr) {
        if (isCopyPtr != 0L) {
            ByteBuffer isCopyBuf = directByteBuffer(isCopyPtr, 1);
            isCopyBuf.put((byte) 1); // always copy since pinning is not supported
        }
        byte[] bytes = Utf8.asUTF(Meta.toHostString(str), true);
        ByteBuffer region = allocateDirect(bytes.length);
        region.put(bytes);
        return byteBufferAddress(region);
    }

    @JniImpl
    public void ReleaseStringUTFChars(@SuppressWarnings("unused") @Host(String.class) StaticObject str, long charsPtr) {
        assert nativeBuffers.containsKey(charsPtr);
        nativeBuffers.remove(charsPtr);
    }

    @JniImpl
    public void ReleaseStringCritical(@SuppressWarnings("unused") @Host(String.class) StaticObject str, long criticalRegionPtr) {
        assert nativeBuffers.containsKey(criticalRegionPtr);
        nativeBuffers.remove(criticalRegionPtr);
    }

    @JniImpl
    public int EnsureLocalCapacity(@SuppressWarnings("unused") int capacity) {
        return JNI_OK;
    }

    @JniImpl
    public void DeleteLocalRef(@SuppressWarnings("unused") Object localRef) {
        // nop
    }

    /**
     * <h3>jint Throw(JNIEnv *env, jthrowable obj);</h3>
     *
     * Causes a {@link java.lang.Throwable} object to be thrown.
     *
     * @param obj a {@link java.lang.Throwable} object.
     *
     * @return 0 on success; a negative value on failure.
     */
    @JniImpl
    public int Throw(@Host(Throwable.class) StaticObject obj) {
        assert getMeta().Throwable.isAssignableFrom(obj.getKlass());
        // The TLS exception slot will be set by the JNI wrapper.
        // Throwing methods always return the default value, in this case 0 (success).
        throw new EspressoException(obj);
    }

    /**
     * <h3>jint ThrowNew(JNIEnv *env, jclass clazz, const char *message);</h3>
     *
     * Constructs an exception object from the specified class with the message specified by message
     * and causes that exception to be thrown.
     *
     * @param clazz a subclass of java.lang.Throwable.
     * @param message the message used to construct the {@link java.lang.Throwable} object. The
     *            string is encoded in modified UTF-8.
     *
     * @return 0 on success; a negative value on failure.
     *
     * @throws EspressoException the newly constructed {@link java.lang.Throwable} object.
     */
    @JniImpl
    public int ThrowNew(@Host(Class.class) StaticObject clazz, String message) {
        StaticObject ex = Meta.initEx(((StaticObjectClass) clazz).getMirror(), message);
        // The TLS exception slot will be set by the JNI wrapper.
        // Throwing methods always return the default value, in this case 0 (success).
        throw new EspressoException(ex);
    }

    /**
     * <h3>jthrowable ExceptionOccurred(JNIEnv *env);</h3>
     *
     * Determines if an exception is being thrown. The exception stays being thrown until either the
     * native code calls {@link #ExceptionClear}, or the Java code handles the exception.
     *
     * @return the exception object that is currently in the process of being thrown, or NULL if no
     *         exception is currently being thrown.
     */
    @JniImpl
    public StaticObject ExceptionOccurred() {
        StaticObject ex = threadLocalPendingException.get();
        if (ex == null) {
            ex = StaticObject.NULL;
        }
        return ex;
    }

    @JniImpl
    public int MonitorEnter(Object obj) {
        getInterpreterToVM().monitorEnter(obj);
        return JNI_OK;
    }

    @JniImpl
    public int MonitorExit(Object obj) {
        getInterpreterToVM().monitorExit(obj);
        return JNI_OK;
    }

    @JniImpl
    public StaticObject NewObjectArray(int length, @Host(Class.class) StaticObjectClass elementClass, @Host(Object.class) StaticObject initialElement) {
        assert !elementClass.getMirror().isPrimitive();
        StaticObjectArray arr = (StaticObjectArray) elementClass.getMirror().allocateArray(length);
        if (length > 0) {
            // Single store check
            getInterpreterToVM().setArrayObject(initialElement, 0, arr);
            Arrays.fill(arr.unwrap(), initialElement);
        }
        return arr;
    }

    @JniImpl
    public void SetObjectArrayElement(StaticObjectArray array, int index, @Host(Object.class) StaticObject value) {
        getInterpreterToVM().setArrayObject(value, index, array);
    }

    @JniImpl
    public StaticObject NewString(long unicodePtr, int len) {
        StaticObject value = StaticObjectArray.wrap(new char[len]);
        SetCharArrayRegion(value, 0, len, unicodePtr);
        StaticObject guestString = getMeta().String.allocateInstance();
        getMeta().String_value.set(guestString, value);
        return guestString;
    }

    @JniImpl
    public void GetStringRegion(@Host(String.class) StaticObject str, int start, int len, long bufPtr) {
        StaticObjectArray chars = (StaticObjectArray) getMeta().String_value.get(str);
        CharBuffer buf = directByteBuffer(bufPtr, len, JavaKind.Char).asCharBuffer();
        buf.put(chars.<char[]> unwrap(), start, len);
    }

    private static String nfiSignature(final ByteString<Type>[] signature, boolean isJni) {
        // ByteString<Signature> descriptor = Signatures.fromJavaString(signature);

        int argCount = Signatures.parameterCount(signature, false);
        StringBuilder sb = new StringBuilder("(");

        boolean first = true;
        if (isJni) {
            sb.append(NativeSimpleType.POINTER); // JNIEnv*
            sb.append(",");
            sb.append(Utils.kindToType(JavaKind.Object, false)); // Receiver or class (for static
                                                                 // methods).
            first = false;
        }
        for (int i = 0; i < argCount; ++i) {
            JavaKind kind = Signatures.parameterKind(signature, i);
            if (!first) {
                sb.append(", ");
            } else {
                first = false;
            }
            sb.append(Utils.kindToType(kind, false));
        }

        sb.append("): ").append(Utils.kindToType(Signatures.returnKind(signature), false));
        return sb.toString();
    }

    @JniImpl
    public int RegisterNative(@Host(Class.class) StaticObject clazz, String name, String signature, @NFIType("POINTER") TruffleObject closure) {
        ByteString<Type> classType = ((StaticObjectClass) clazz).getMirror().getType();

        final TruffleObject boundNative = NativeLibrary.bind(closure, nfiSignature(getSignatures().make(Signatures.fromJavaString(signature)), true));

        Substitutions.EspressoRootNodeFactory factory = new Substitutions.EspressoRootNodeFactory() {
            @Override
            public EspressoRootNode spawnNode(Method method) {
                return new VmNativeNode(boundNative, true, method);
            }
        };

        getSubstitutions().registerRuntimeSubstitution(classType, ByteString.fromJavaString(name), Signatures.fromJavaString(signature), factory, true);
        return JNI_OK;
    }

    @JniImpl
    public int GetStringUTFLength(@Host(String.class) StaticObject string) {
        return Utf8.UTFLength(Meta.toHostString(string));
    }

    @JniImpl
    public void GetStringUTFRegion(@Host(String.class) StaticObject str, int start, int len, long bufPtr) {
        byte[] bytes = Utf8.asUTF(Meta.toHostString(str), start, len, true); // always 0 terminated.
        ByteBuffer buf = directByteBuffer(bufPtr, bytes.length, JavaKind.Byte);
        buf.put(bytes);
    }

    /**
     * Loads a class from a buffer of raw class data. The buffer containing the raw class data is
     * not referenced by the VM after the DefineClass call returns, and it may be discarded if
     * desired.
     *
     * @param name the name of the class or interface to be defined. The string is encoded in
     *            modified UTF-8.
     * @param loader a class loader assigned to the defined class.
     * @param bufPtr buffer containing the .class file data.
     * @param bufLen buffer length.
     *
     * @return Returns a Java class object or NULL if an error occurs.
     */
    @JniImpl
    public @Host(Class.class) StaticObject DefineClass(String name, @Host(ClassLoader.class) StaticObject loader, long bufPtr, int bufLen) {
        // TODO(peterssen): Propagete errors and verifications, e.g. no class win the java package.
        return getVM().JVM_DefineClass(name, loader, bufPtr, bufLen, StaticObject.NULL);
    }

    // JavaVM **vm);

    @JniImpl
    public int GetJavaVM(long vmPtr) {
        ByteBuffer buf = directByteBuffer(vmPtr, 1, JavaKind.Long); // 64 bits pointer
        buf.putLong(getVM().getJavaVM());
        return JNI_OK;
    }

    /**
     * <h3>jboolean IsAssignableFrom(JNIEnv *env, jclass clazz1, jclass clazz2);</h3>
     *
     * Determines whether an object of clazz1 can be safely cast to clazz2.
     *
     * @param clazz1 the first class argument.
     * @param clazz2 the second class argument.
     *
     * @return Returns JNI_TRUE if either of the following is true:
     *         <ul>
     *         <li>The first and second class arguments refer to the same Java class.
     *         <li>The first class is a subclass of the second class.
     *         <li>The first class has the second class as one of its interfaces.
     *         </ul>
     */
    @JniImpl
    public boolean IsAssignableFrom(@Host(Class.class) StaticObject clazz1, @Host(Class.class) StaticObject clazz2) {
        Klass klass2 = ((StaticObjectClass) clazz2).getMirror();
        return klass2.isAssignableFrom(((StaticObjectClass) clazz1).getMirror());
    }

    /**
     * <h3>jboolean IsInstanceOf(JNIEnv *env, jobject obj, jclass clazz);</h3>
     *
     * Tests whether an object is an instance of a class.
     *
     * @param obj a Java object.
     * @param clazz a Java class object.
     *
     * @return Returns {@code JNI_TRUE} if obj can be cast to clazz; otherwise, returns
     *         {@code JNI_FALSE}. <b>A NULL object can be cast to any class.</b>
     */
    @JniImpl
    public boolean IsInstanceOf(@Host(Object.class) StaticObject obj, @Host(Class.class) StaticObjectClass clazz) {
        if (StaticObject.isNull(obj)) {
            return true;
        }
        return getInterpreterToVM().instanceOf(obj, clazz.getMirror());
    }

    /**
     * <h3>jobject GetObjectArrayElement(JNIEnv *env, jobjectArray array, jsize index);</h3>
     *
     * Returns an element of an Object array.
     *
     * @param array a Java array.
     * @param index array index.
     *
     * @return a Java object.
     *
     * @throws ArrayIndexOutOfBoundsException if index does not specify a valid index in the array.
     */
    @JniImpl
    public @Host(Object.class) StaticObject GetObjectArrayElement(StaticObject array, int index) {
        return getInterpreterToVM().getArrayObject(index, array);
    }
}
