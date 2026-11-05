//! The JNI entry points `ai.storyteller.wordcraft.Native` calls. JNI finds native methods by
//! their exported symbol name, and an exported name counts as unsafe code: these three are the
//! only unsafe code in WordCraft. Each hands straight to safe Rust in lib.rs; `with_env` turns a
//! panic into a Java exception (caught by the app) instead of aborting the process.
use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jfloat, jint};

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_wordcraft_Native_call<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    method: JString<'caller>,
    params: JString<'caller>,
) -> JString<'caller> {
    env.with_env(|env| -> jni::errors::Result<JString<'caller>> {
        let out = crate::call(&method.to_string(), &params.to_string());
        JString::from_str(env, out)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_wordcraft_Native_render<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    page: jint,
    scale: jfloat,
) -> JByteArray<'caller> {
    env.with_env(|env| env.byte_array_from_slice(&crate::render(page, scale))).resolve::<ThrowRuntimeExAndDefault>()
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_wordcraft_Native_save<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    name: JString<'caller>,
) -> JByteArray<'caller> {
    env.with_env(|env| env.byte_array_from_slice(&crate::save(&name.to_string()))).resolve::<ThrowRuntimeExAndDefault>()
}
