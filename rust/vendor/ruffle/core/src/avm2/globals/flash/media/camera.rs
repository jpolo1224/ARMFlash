//! `flash.media.Camera` natives (ARMFlash): the device camera, through `crate::camera`.

use crate::avm2::activation::Activation;
use crate::avm2::function::FunctionArgs;
use crate::avm2::parameters::ParametersExt;
use crate::avm2::value::Value;
use crate::avm2::{ArrayObject, ArrayStorage, Error};
use crate::camera::{self, CameraPermission};
use crate::string::AvmString;

pub fn get_names<'gc>(
    activation: &mut Activation<'_, 'gc>,
    _this: Value<'gc>,
    _args: FunctionArgs<'_, 'gc>,
) -> Result<Value<'gc>, Error<'gc>> {
    let storage: ArrayStorage<'gc> = camera::names()
        .into_iter()
        .map(|name| Value::from(AvmString::new_utf8(activation.gc(), name)))
        .collect();
    Ok(ArrayObject::from_storage(activation.context, storage).into())
}

pub fn start_capture<'gc>(
    _activation: &mut Activation<'_, 'gc>,
    _this: Value<'gc>,
    args: FunctionArgs<'_, 'gc>,
) -> Result<Value<'gc>, Error<'gc>> {
    if let Some(backend) = camera::backend() {
        let index = args.get_i32(0).max(0) as usize;
        let width = args.get_i32(1).clamp(1, 4096) as u32;
        let height = args.get_i32(2).clamp(1, 4096) as u32;
        backend.start(index, width, height, args.get_f64(3).clamp(1.0, 60.0));
    }
    Ok(Value::Undefined)
}

pub fn permission<'gc>(
    _activation: &mut Activation<'_, 'gc>,
    _this: Value<'gc>,
    _args: FunctionArgs<'_, 'gc>,
) -> Result<Value<'gc>, Error<'gc>> {
    Ok(match camera::backend().map(|b| b.permission()) {
        Some(CameraPermission::Allowed) => 1,
        Some(CameraPermission::Asking) => 0,
        Some(CameraPermission::Refused) | None => -1,
    }
    .into())
}

pub fn frame_width<'gc>(
    _activation: &mut Activation<'_, 'gc>,
    _this: Value<'gc>,
    _args: FunctionArgs<'_, 'gc>,
) -> Result<Value<'gc>, Error<'gc>> {
    Ok(camera::frame().map_or(0, |f| f.width as i32).into())
}

pub fn frame_height<'gc>(
    _activation: &mut Activation<'_, 'gc>,
    _this: Value<'gc>,
    _args: FunctionArgs<'_, 'gc>,
) -> Result<Value<'gc>, Error<'gc>> {
    Ok(camera::frame().map_or(0, |f| f.height as i32).into())
}

pub fn get_activity_level<'gc>(
    _activation: &mut Activation<'_, 'gc>,
    _this: Value<'gc>,
    _args: FunctionArgs<'_, 'gc>,
) -> Result<Value<'gc>, Error<'gc>> {
    Ok(camera::backend().map_or(-1.0, |b| b.activity_level()).into())
}

pub fn get_current_fps<'gc>(
    _activation: &mut Activation<'_, 'gc>,
    _this: Value<'gc>,
    _args: FunctionArgs<'_, 'gc>,
) -> Result<Value<'gc>, Error<'gc>> {
    Ok(camera::backend().map_or(0.0, |b| b.current_fps()).into())
}
