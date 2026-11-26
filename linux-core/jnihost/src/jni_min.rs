//! JNI 最小绑定 —— 只保留本文件用到的那几个函数。
//!
//! 为什么不直接用 `jni` / `jni-sys` crate：
//! `jni` 依赖 `jni-sys 0.3`（build script），`jni-sys 0.4` 依赖 `jni-sys-macros`
//! （proc-macro）。这两类 crate 编译时都要**为宿主平台链接**一个 Windows/Linux
//! 二进制，而交叉编译 Android 的机器上不一定有宿主链接器（本机就缺 Windows SDK），
//! 于是整个 `.so` 编不出来。这里只抄 `JNINativeInterface_` 的前 170 个字段，
//! 零依赖、零 build script。
//!
//! 类型与字段顺序照抄 jni-sys 0.4.1（MIT OR Apache-2.0），未改语义。

#![allow(non_snake_case, non_camel_case_types, dead_code)]

use core::ffi::{c_char, c_void};

pub type va_list = *mut c_void;

pub type jint = i32;
pub type jlong = i64;
pub type jbyte = i8;
pub type jboolean = bool;
pub type jchar = u16;
pub type jshort = i16;
pub type jfloat = f32;
pub type jdouble = f64;
pub type jsize = jint;


pub enum _jobject {}
pub type jobject = *mut _jobject;
pub type jclass = jobject;
pub type jthrowable = jobject;
pub type jstring = jobject;
pub type jarray = jobject;
pub type jbooleanArray = jarray;
pub type jbyteArray = jarray;
pub type jcharArray = jarray;
pub type jshortArray = jarray;
pub type jintArray = jarray;
pub type jlongArray = jarray;
pub type jfloatArray = jarray;
pub type jdoubleArray = jarray;
pub type jobjectArray = jarray;
pub type jweak = jobject;

#[repr(C)]
#[derive(Copy)]
pub union jvalue {
    pub z: jboolean,
    pub b: jbyte,
    pub c: jchar,
    pub s: jshort,
    pub i: jint,
    pub j: jlong,
    pub f: jfloat,
    pub d: jdouble,
    pub l: jobject,
}

impl Clone for jvalue {
    fn clone(&self) -> Self {
        *self
    }
}
impl core::fmt::Debug for jvalue {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        let b = unsafe { self.b };
        // For all except `jboolean` then any bitwise pattern is a valid value
        // so even though we don't know which specific type the given `jvalue`
        // represents we can effectively cast it to all possible types.
        f.debug_struct("jvalue")
            .field(
                "z",
                &if b == 0 {
                    "false"
                } else if b == 1 {
                    "true"
                } else {
                    "invalid"
                },
            )
            .field("b", unsafe { &self.b })
            .field("c", unsafe { &self.c })
            .field("s", unsafe { &self.s })
            .field("i", unsafe { &self.i })
            .field("j", unsafe { &self.j })
            .field("f", unsafe { &self.f })
            .field("d", unsafe { &self.d })
            .field("l", unsafe { &self.l })
            .finish()
    }
}


pub enum _jfieldID {}
pub type jfieldID = *mut _jfieldID;

pub enum _jmethodID {}
pub type jmethodID = *mut _jmethodID;

#[derive(Clone, Copy, Debug)]
#[repr(C)]
pub enum jobjectRefType {
    JNIInvalidRefType = 0,
    JNILocalRefType = 1,
    JNIGlobalRefType = 2,
    JNIWeakGlobalRefType = 3,
}

pub const JNI_FALSE: jboolean = false;
pub const JNI_TRUE: jboolean = true;

pub const JNI_OK: jint = 0;
pub const JNI_ERR: jint = -1;
pub const JNI_EDETACHED: jint = -2;
pub const JNI_EVERSION: jint = -3;
pub const JNI_ENOMEM: jint = -4;
pub const JNI_EEXIST: jint = -5;
pub const JNI_EINVAL: jint = -6;

pub const JNI_COMMIT: jint = 1;
pub const JNI_ABORT: jint = 2;

pub const JNI_VERSION_1_1: jint = 0x00010001;
pub const JNI_VERSION_1_2: jint = 0x00010002;
pub const JNI_VERSION_1_4: jint = 0x00010004;
pub const JNI_VERSION_1_6: jint = 0x00010006;
pub const JNI_VERSION_1_8: jint = 0x00010008;
pub const JNI_VERSION_9: jint = 0x00090000;
pub const JNI_VERSION_10: jint = 0x000a0000;
pub const JNI_VERSION_19: jint = 0x00130000;
pub const JNI_VERSION_20: jint = 0x00140000;
pub const JNI_VERSION_21: jint = 0x00150000;
pub const JNI_VERSION_24: jint = 0x00180000;

#[repr(C)]
#[derive(Copy, Debug)]
pub struct JNINativeMethod {
    pub name: *mut c_char,
    pub signature: *mut c_char,
    pub fnPtr: *mut c_void,
}

impl Clone for JNINativeMethod {
    fn clone(&self) -> Self {
        *self
    }
}

/// JNI 函数表指针（`jni-sys` 原名同此）。
pub type JNIEnv = *const JNINativeInterface_;

#[repr(C)]
#[derive(Copy, Clone)]
pub struct JNINativeInterface_ {
    pub reserved0: *mut c_void,
    pub reserved1: *mut c_void,
    pub reserved2: *mut c_void,
    pub reserved3: *mut c_void,
    pub GetVersion: unsafe extern "system" fn(env: *mut JNIEnv) -> jint,
    pub DefineClass: unsafe extern "system" fn(
        env: *mut JNIEnv,
        name: *const c_char,
        loader: jobject,
        buf: *const jbyte,
        len: jsize,
    ) -> jclass,
    pub FindClass: unsafe extern "system" fn(env: *mut JNIEnv, name: *const c_char) -> jclass,
    pub FromReflectedMethod:
        unsafe extern "system" fn(env: *mut JNIEnv, method: jobject) -> jmethodID,
    pub FromReflectedField: unsafe extern "system" fn(env: *mut JNIEnv, field: jobject) -> jfieldID,
    pub ToReflectedMethod: unsafe extern "system" fn(
        env: *mut JNIEnv,
        cls: jclass,
        methodID: jmethodID,
        isStatic: jboolean,
    ) -> jobject,
    pub GetSuperclass: unsafe extern "system" fn(env: *mut JNIEnv, sub: jclass) -> jclass,
    pub IsAssignableFrom:
        unsafe extern "system" fn(env: *mut JNIEnv, sub: jclass, sup: jclass) -> jboolean,
    pub ToReflectedField: unsafe extern "system" fn(
        env: *mut JNIEnv,
        cls: jclass,
        fieldID: jfieldID,
        isStatic: jboolean,
    ) -> jobject,
    pub Throw: unsafe extern "system" fn(env: *mut JNIEnv, obj: jthrowable) -> jint,
    pub ThrowNew:
        unsafe extern "system" fn(env: *mut JNIEnv, clazz: jclass, msg: *const c_char) -> jint,
    pub ExceptionOccurred: unsafe extern "system" fn(env: *mut JNIEnv) -> jthrowable,
    pub ExceptionDescribe: unsafe extern "system" fn(env: *mut JNIEnv),
    pub ExceptionClear: unsafe extern "system" fn(env: *mut JNIEnv),
    pub FatalError: unsafe extern "system" fn(env: *mut JNIEnv, msg: *const c_char) -> !,
    pub PushLocalFrame: unsafe extern "system" fn(env: *mut JNIEnv, capacity: jint) -> jint,
    pub PopLocalFrame: unsafe extern "system" fn(env: *mut JNIEnv, result: jobject) -> jobject,
    pub NewGlobalRef: unsafe extern "system" fn(env: *mut JNIEnv, lobj: jobject) -> jobject,
    pub DeleteGlobalRef: unsafe extern "system" fn(env: *mut JNIEnv, gref: jobject),
    pub DeleteLocalRef: unsafe extern "system" fn(env: *mut JNIEnv, obj: jobject),
    pub IsSameObject:
        unsafe extern "system" fn(env: *mut JNIEnv, obj1: jobject, obj2: jobject) -> jboolean,
    pub NewLocalRef: unsafe extern "system" fn(env: *mut JNIEnv, ref_: jobject) -> jobject,
    pub EnsureLocalCapacity: unsafe extern "system" fn(env: *mut JNIEnv, capacity: jint) -> jint,
    pub AllocObject: unsafe extern "system" fn(env: *mut JNIEnv, clazz: jclass) -> jobject,
    pub NewObject:
        unsafe extern "C" fn(env: *mut JNIEnv, clazz: jclass, methodID: jmethodID, ...) -> jobject,
    pub NewObjectV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        methodID: jmethodID,
        args: va_list,
    ) -> jobject,
    pub NewObjectA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jobject,
    pub GetObjectClass: unsafe extern "system" fn(env: *mut JNIEnv, obj: jobject) -> jclass,
    pub IsInstanceOf:
        unsafe extern "system" fn(env: *mut JNIEnv, obj: jobject, clazz: jclass) -> jboolean,
    pub GetMethodID: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        name: *const c_char,
        sig: *const c_char,
    ) -> jmethodID,
    pub CallObjectMethod:
        unsafe extern "C" fn(env: *mut JNIEnv, obj: jobject, methodID: jmethodID, ...) -> jobject,
    pub CallObjectMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        methodID: jmethodID,
        args: va_list,
    ) -> jobject,
    pub CallObjectMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jobject,
    pub CallBooleanMethod:
        unsafe extern "C" fn(env: *mut JNIEnv, obj: jobject, methodID: jmethodID, ...) -> jboolean,
    pub CallBooleanMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        methodID: jmethodID,
        args: va_list,
    ) -> jboolean,

    pub CallBooleanMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jboolean,

    pub CallByteMethod:
        unsafe extern "C" fn(env: *mut JNIEnv, obj: jobject, methodID: jmethodID, ...) -> jbyte,

    pub CallByteMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        methodID: jmethodID,
        args: va_list,
    ) -> jbyte,

    pub CallByteMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jbyte,

    pub CallCharMethod:
        unsafe extern "C" fn(env: *mut JNIEnv, obj: jobject, methodID: jmethodID, ...) -> jchar,

    pub CallCharMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        methodID: jmethodID,
        args: va_list,
    ) -> jchar,

    pub CallCharMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jchar,

    pub CallShortMethod:
        unsafe extern "C" fn(env: *mut JNIEnv, obj: jobject, methodID: jmethodID, ...) -> jshort,

    pub CallShortMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        methodID: jmethodID,
        args: va_list,
    ) -> jshort,

    pub CallShortMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jshort,

    pub CallIntMethod:
        unsafe extern "C" fn(env: *mut JNIEnv, obj: jobject, methodID: jmethodID, ...) -> jint,

    pub CallIntMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        methodID: jmethodID,
        args: va_list,
    ) -> jint,

    pub CallIntMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jint,

    pub CallLongMethod:
        unsafe extern "C" fn(env: *mut JNIEnv, obj: jobject, methodID: jmethodID, ...) -> jlong,

    pub CallLongMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        methodID: jmethodID,
        args: va_list,
    ) -> jlong,

    pub CallLongMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jlong,

    pub CallFloatMethod:
        unsafe extern "C" fn(env: *mut JNIEnv, obj: jobject, methodID: jmethodID, ...) -> jfloat,

    pub CallFloatMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        methodID: jmethodID,
        args: va_list,
    ) -> jfloat,

    pub CallFloatMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jfloat,

    pub CallDoubleMethod:
        unsafe extern "C" fn(env: *mut JNIEnv, obj: jobject, methodID: jmethodID, ...) -> jdouble,

    pub CallDoubleMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        methodID: jmethodID,
        args: va_list,
    ) -> jdouble,

    pub CallDoubleMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jdouble,

    pub CallVoidMethod:
        unsafe extern "C" fn(env: *mut JNIEnv, obj: jobject, methodID: jmethodID, ...),
    pub CallVoidMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        methodID: jmethodID,
        args: va_list,
    ),

    pub CallVoidMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        methodID: jmethodID,
        args: *const jvalue,
    ),

    pub CallNonvirtualObjectMethod: unsafe extern "C" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        ...
    ) -> jobject,

    pub CallNonvirtualObjectMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        args: va_list,
    ) -> jobject,

    pub CallNonvirtualObjectMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jobject,

    pub CallNonvirtualBooleanMethod: unsafe extern "C" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        ...
    ) -> jboolean,

    pub CallNonvirtualBooleanMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        args: va_list,
    ) -> jboolean,

    pub CallNonvirtualBooleanMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jboolean,

    pub CallNonvirtualByteMethod: unsafe extern "C" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        ...
    ) -> jbyte,

    pub CallNonvirtualByteMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        args: va_list,
    ) -> jbyte,

    pub CallNonvirtualByteMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jbyte,

    pub CallNonvirtualCharMethod: unsafe extern "C" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        ...
    ) -> jchar,

    pub CallNonvirtualCharMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        args: va_list,
    ) -> jchar,

    pub CallNonvirtualCharMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jchar,

    pub CallNonvirtualShortMethod: unsafe extern "C" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        ...
    ) -> jshort,

    pub CallNonvirtualShortMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        args: va_list,
    ) -> jshort,

    pub CallNonvirtualShortMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jshort,

    pub CallNonvirtualIntMethod: unsafe extern "C" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        ...
    ) -> jint,

    pub CallNonvirtualIntMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        args: va_list,
    ) -> jint,

    pub CallNonvirtualIntMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jint,

    pub CallNonvirtualLongMethod: unsafe extern "C" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        ...
    ) -> jlong,

    pub CallNonvirtualLongMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        args: va_list,
    ) -> jlong,

    pub CallNonvirtualLongMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jlong,

    pub CallNonvirtualFloatMethod: unsafe extern "C" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        ...
    ) -> jfloat,

    pub CallNonvirtualFloatMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        args: va_list,
    ) -> jfloat,

    pub CallNonvirtualFloatMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jfloat,

    pub CallNonvirtualDoubleMethod: unsafe extern "C" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        ...
    ) -> jdouble,

    pub CallNonvirtualDoubleMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        args: va_list,
    ) -> jdouble,

    pub CallNonvirtualDoubleMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jdouble,

    pub CallNonvirtualVoidMethod: unsafe extern "C" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        ...
    ),

    pub CallNonvirtualVoidMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        args: va_list,
    ),

    pub CallNonvirtualVoidMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        obj: jobject,
        clazz: jclass,
        methodID: jmethodID,
        args: *const jvalue,
    ),

    pub GetFieldID: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        name: *const c_char,
        sig: *const c_char,
    ) -> jfieldID,

    pub GetObjectField:
        unsafe extern "system" fn(env: *mut JNIEnv, obj: jobject, fieldID: jfieldID) -> jobject,

    pub GetBooleanField:
        unsafe extern "system" fn(env: *mut JNIEnv, obj: jobject, fieldID: jfieldID) -> jboolean,

    pub GetByteField:
        unsafe extern "system" fn(env: *mut JNIEnv, obj: jobject, fieldID: jfieldID) -> jbyte,

    pub GetCharField:
        unsafe extern "system" fn(env: *mut JNIEnv, obj: jobject, fieldID: jfieldID) -> jchar,

    pub GetShortField:
        unsafe extern "system" fn(env: *mut JNIEnv, obj: jobject, fieldID: jfieldID) -> jshort,

    pub GetIntField:
        unsafe extern "system" fn(env: *mut JNIEnv, obj: jobject, fieldID: jfieldID) -> jint,

    pub GetLongField:
        unsafe extern "system" fn(env: *mut JNIEnv, obj: jobject, fieldID: jfieldID) -> jlong,

    pub GetFloatField:
        unsafe extern "system" fn(env: *mut JNIEnv, obj: jobject, fieldID: jfieldID) -> jfloat,

    pub GetDoubleField:
        unsafe extern "system" fn(env: *mut JNIEnv, obj: jobject, fieldID: jfieldID) -> jdouble,

    pub SetObjectField:
        unsafe extern "system" fn(env: *mut JNIEnv, obj: jobject, fieldID: jfieldID, val: jobject),

    pub SetBooleanField:
        unsafe extern "system" fn(env: *mut JNIEnv, obj: jobject, fieldID: jfieldID, val: jboolean),

    pub SetByteField:
        unsafe extern "system" fn(env: *mut JNIEnv, obj: jobject, fieldID: jfieldID, val: jbyte),

    pub SetCharField:
        unsafe extern "system" fn(env: *mut JNIEnv, obj: jobject, fieldID: jfieldID, val: jchar),

    pub SetShortField:
        unsafe extern "system" fn(env: *mut JNIEnv, obj: jobject, fieldID: jfieldID, val: jshort),

    pub SetIntField:
        unsafe extern "system" fn(env: *mut JNIEnv, obj: jobject, fieldID: jfieldID, val: jint),

    pub SetLongField:
        unsafe extern "system" fn(env: *mut JNIEnv, obj: jobject, fieldID: jfieldID, val: jlong),

    pub SetFloatField:
        unsafe extern "system" fn(env: *mut JNIEnv, obj: jobject, fieldID: jfieldID, val: jfloat),

    pub SetDoubleField:
        unsafe extern "system" fn(env: *mut JNIEnv, obj: jobject, fieldID: jfieldID, val: jdouble),

    pub GetStaticMethodID: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        name: *const c_char,
        sig: *const c_char,
    ) -> jmethodID,

    pub CallStaticObjectMethod:
        unsafe extern "C" fn(env: *mut JNIEnv, clazz: jclass, methodID: jmethodID, ...) -> jobject,

    pub CallStaticObjectMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        methodID: jmethodID,
        args: va_list,
    ) -> jobject,

    pub CallStaticObjectMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jobject,

    pub CallStaticBooleanMethod:
        unsafe extern "C" fn(env: *mut JNIEnv, clazz: jclass, methodID: jmethodID, ...) -> jboolean,

    pub CallStaticBooleanMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        methodID: jmethodID,
        args: va_list,
    ) -> jboolean,

    pub CallStaticBooleanMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jboolean,

    pub CallStaticByteMethod:
        unsafe extern "C" fn(env: *mut JNIEnv, clazz: jclass, methodID: jmethodID, ...) -> jbyte,

    pub CallStaticByteMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        methodID: jmethodID,
        args: va_list,
    ) -> jbyte,

    pub CallStaticByteMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jbyte,

    pub CallStaticCharMethod:
        unsafe extern "C" fn(env: *mut JNIEnv, clazz: jclass, methodID: jmethodID, ...) -> jchar,

    pub CallStaticCharMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        methodID: jmethodID,
        args: va_list,
    ) -> jchar,

    pub CallStaticCharMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jchar,

    pub CallStaticShortMethod:
        unsafe extern "C" fn(env: *mut JNIEnv, clazz: jclass, methodID: jmethodID, ...) -> jshort,

    pub CallStaticShortMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        methodID: jmethodID,
        args: va_list,
    ) -> jshort,

    pub CallStaticShortMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jshort,

    pub CallStaticIntMethod:
        unsafe extern "C" fn(env: *mut JNIEnv, clazz: jclass, methodID: jmethodID, ...) -> jint,

    pub CallStaticIntMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        methodID: jmethodID,
        args: va_list,
    ) -> jint,

    pub CallStaticIntMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jint,

    pub CallStaticLongMethod:
        unsafe extern "C" fn(env: *mut JNIEnv, clazz: jclass, methodID: jmethodID, ...) -> jlong,

    pub CallStaticLongMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        methodID: jmethodID,
        args: va_list,
    ) -> jlong,

    pub CallStaticLongMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jlong,

    pub CallStaticFloatMethod:
        unsafe extern "C" fn(env: *mut JNIEnv, clazz: jclass, methodID: jmethodID, ...) -> jfloat,

    pub CallStaticFloatMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        methodID: jmethodID,
        args: va_list,
    ) -> jfloat,

    pub CallStaticFloatMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jfloat,

    pub CallStaticDoubleMethod:
        unsafe extern "C" fn(env: *mut JNIEnv, clazz: jclass, methodID: jmethodID, ...) -> jdouble,

    pub CallStaticDoubleMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        methodID: jmethodID,
        args: va_list,
    ) -> jdouble,

    pub CallStaticDoubleMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jdouble,

    pub CallStaticVoidMethod:
        unsafe extern "C" fn(env: *mut JNIEnv, cls: jclass, methodID: jmethodID, ...),
    pub CallStaticVoidMethodV: unsafe extern "system" fn(
        env: *mut JNIEnv,
        cls: jclass,
        methodID: jmethodID,
        args: va_list,
    ),

    pub CallStaticVoidMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        cls: jclass,
        methodID: jmethodID,
        args: *const jvalue,
    ),

    pub GetStaticFieldID: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        name: *const c_char,
        sig: *const c_char,
    ) -> jfieldID,

    pub GetStaticObjectField:
        unsafe extern "system" fn(env: *mut JNIEnv, clazz: jclass, fieldID: jfieldID) -> jobject,

    pub GetStaticBooleanField:
        unsafe extern "system" fn(env: *mut JNIEnv, clazz: jclass, fieldID: jfieldID) -> jboolean,

    pub GetStaticByteField:
        unsafe extern "system" fn(env: *mut JNIEnv, clazz: jclass, fieldID: jfieldID) -> jbyte,

    pub GetStaticCharField:
        unsafe extern "system" fn(env: *mut JNIEnv, clazz: jclass, fieldID: jfieldID) -> jchar,

    pub GetStaticShortField:
        unsafe extern "system" fn(env: *mut JNIEnv, clazz: jclass, fieldID: jfieldID) -> jshort,

    pub GetStaticIntField:
        unsafe extern "system" fn(env: *mut JNIEnv, clazz: jclass, fieldID: jfieldID) -> jint,

    pub GetStaticLongField:
        unsafe extern "system" fn(env: *mut JNIEnv, clazz: jclass, fieldID: jfieldID) -> jlong,

    pub GetStaticFloatField:
        unsafe extern "system" fn(env: *mut JNIEnv, clazz: jclass, fieldID: jfieldID) -> jfloat,

    pub GetStaticDoubleField:
        unsafe extern "system" fn(env: *mut JNIEnv, clazz: jclass, fieldID: jfieldID) -> jdouble,

    pub SetStaticObjectField: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        fieldID: jfieldID,
        value: jobject,
    ),

    pub SetStaticBooleanField: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        fieldID: jfieldID,
        value: jboolean,
    ),

    pub SetStaticByteField:
        unsafe extern "system" fn(env: *mut JNIEnv, clazz: jclass, fieldID: jfieldID, value: jbyte),

    pub SetStaticCharField:
        unsafe extern "system" fn(env: *mut JNIEnv, clazz: jclass, fieldID: jfieldID, value: jchar),

    pub SetStaticShortField: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        fieldID: jfieldID,
        value: jshort,
    ),

    pub SetStaticIntField:
        unsafe extern "system" fn(env: *mut JNIEnv, clazz: jclass, fieldID: jfieldID, value: jint),

    pub SetStaticLongField:
        unsafe extern "system" fn(env: *mut JNIEnv, clazz: jclass, fieldID: jfieldID, value: jlong),

    pub SetStaticFloatField: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        fieldID: jfieldID,
        value: jfloat,
    ),

    pub SetStaticDoubleField: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        fieldID: jfieldID,
        value: jdouble,
    ),

    pub NewString:
        unsafe extern "system" fn(env: *mut JNIEnv, unicode: *const jchar, len: jsize) -> jstring,

    pub GetStringLength: unsafe extern "system" fn(env: *mut JNIEnv, str: jstring) -> jsize,
    pub GetStringChars: unsafe extern "system" fn(
        env: *mut JNIEnv,
        str: jstring,
        isCopy: *mut jboolean,
    ) -> *const jchar,

    pub ReleaseStringChars:
        unsafe extern "system" fn(env: *mut JNIEnv, str: jstring, chars: *const jchar),
    pub NewStringUTF: unsafe extern "system" fn(env: *mut JNIEnv, utf: *const c_char) -> jstring,
    pub GetStringUTFLength: unsafe extern "system" fn(env: *mut JNIEnv, str: jstring) -> jsize,
    pub GetStringUTFChars: unsafe extern "system" fn(
        env: *mut JNIEnv,
        str: jstring,
        isCopy: *mut jboolean,
    ) -> *const c_char,

    pub ReleaseStringUTFChars:
        unsafe extern "system" fn(env: *mut JNIEnv, str: jstring, chars: *const c_char),
}
