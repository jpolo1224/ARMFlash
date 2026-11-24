//! Camera object.
//!
//! ARMFlash: the device camera, through `crate::camera`. `Camera.get()` gives the camera (one
//! object per camera, as in Flash), `Video.attachVideo(camera)` shows it, and `onStatus` /
//! `onActivity` are called as the permission answer and the motion change.

use crate::avm1::activation::Activation;
use crate::avm1::error::Error;
use crate::avm1::property::Attribute;
use crate::avm1::property_decl::{DeclContext, PropertyOrder, StaticDeclarations, SystemClass};
use crate::avm1::{ArrayBuilder, ExecutionReason, NativeObject, Object, Value};
use crate::avm1_stub;
use crate::camera::{self, CameraPermission};
use crate::string::AvmString;
use crate::timer::TimerCallback;
use gc_arena::{Collect, Gc};
use ruffle_macros::istr;
use std::cell::RefCell;

const PROTO_DECLS: StaticDeclarations = declare_static_properties! {
    use fn method;
    "setMode" => method(SET_MODE; DONT_ENUM | DONT_DELETE);
    "setQuality" => method(SET_QUALITY; DONT_ENUM | DONT_DELETE);
    "setKeyFrameInterval" => method(SET_KEY_FRAME_INTERVAL; DONT_ENUM | DONT_DELETE);
    "setMotionLevel" => method(SET_MOTION_LEVEL; DONT_ENUM | DONT_DELETE);
    "setLoopback" => method(SET_LOOPBACK; DONT_ENUM | DONT_DELETE);
    "setCursor" => method(SET_CURSOR; DONT_ENUM | DONT_DELETE);
    "activityLevel" => property(GET_ACTIVITY_LEVEL; DONT_ENUM | DONT_DELETE);
    "bandwidth" => property(GET_BANDWIDTH; DONT_ENUM | DONT_DELETE);
    "currentFps" => property(GET_CURRENT_FPS; DONT_ENUM | DONT_DELETE);
    "fps" => property(GET_FPS; DONT_ENUM | DONT_DELETE);
    "height" => property(GET_HEIGHT; DONT_ENUM | DONT_DELETE);
    "index" => property(GET_INDEX; DONT_ENUM | DONT_DELETE);
    "keyFrameInterval" => property(GET_KEY_FRAME_INTERVAL; DONT_ENUM | DONT_DELETE);
    "loopback" => property(GET_LOOPBACK; DONT_ENUM | DONT_DELETE);
    "motionLevel" => property(GET_MOTION_LEVEL; DONT_ENUM | DONT_DELETE);
    "motionTimeout" => property(GET_MOTION_TIMEOUT; DONT_ENUM | DONT_DELETE);
    "muted" => property(GET_MUTED; DONT_ENUM | DONT_DELETE);
    "name" => property(GET_NAME; DONT_ENUM | DONT_DELETE);
    "quality" => property(GET_QUALITY; DONT_ENUM | DONT_DELETE);
    "width" => property(GET_WIDTH; DONT_ENUM | DONT_DELETE);
    // Called by the camera's own timer: the permission answer and the motion.
    "__ruffleCameraTick" => method(TICK; DONT_ENUM | DONT_DELETE);
};

const OBJECT_DECLS: StaticDeclarations = declare_static_properties! {
    // In playerglobals.swf, this is a bytecode function delegating to the internal native impl.
    "get" => function(|a, this, args| method(a, this, args, method::INTERNAL_GET));
    use fn method;
    "names" => property(GET_NAMES);
};

pub fn create_class<'gc>(
    context: &mut DeclContext<'_, 'gc>,
    super_proto: Object<'gc>,
) -> SystemClass<'gc> {
    let class = context.empty_class(super_proto, PropertyOrder::PrototypeLast);
    context.define_properties_on(class.proto, PROTO_DECLS(context));
    context.define_properties_on(class.constr, OBJECT_DECLS(context));
    class
}

/// One camera's settings and what its scripts have been told.
#[derive(Clone, Debug, Collect)]
#[collect(require_static)]
pub struct CameraState {
    index: u32,
    name: String,
    width: i32,
    height: i32,
    fps: f64,
    bandwidth: i32,
    quality: i32,
    key_frame_interval: i32,
    loopback: bool,
    motion_level: i32,
    motion_timeout: i32,
    muted: bool,
    active: bool,
    /// Ticks (a tenth of a second each) since the motion last went over motionLevel.
    quiet_ticks: i32,
}

impl CameraState {
    fn new(index: u32, name: String) -> Self {
        Self {
            index,
            name,
            width: 160,
            height: 120,
            fps: 15.0,
            bandwidth: 16384,
            quality: 0,
            key_frame_interval: 15,
            loopback: false,
            motion_level: 50,
            motion_timeout: 2000,
            muted: true,
            active: false,
            quiet_ticks: 0,
        }
    }

    fn start(&self) {
        if let Some(b) = camera::backend() {
            b.start(self.index as usize, self.width.max(1) as u32, self.height.max(1) as u32, self.fps.clamp(1.0, 60.0));
        }
    }
}

pub type CameraObject<'gc> = Gc<'gc, RefCell<CameraState>>;

pub mod method {
    pub const SET_MODE: u16 = 0;
    pub const SET_QUALITY: u16 = 1;
    pub const SET_KEY_FRAME_INTERVAL: u16 = 2;
    pub const SET_MOTION_LEVEL: u16 = 3;
    pub const SET_LOOPBACK: u16 = 4;
    pub const SET_CURSOR: u16 = 5;

    pub const GET_ACTIVITY_LEVEL: u16 = 100;
    pub const GET_BANDWIDTH: u16 = 101;
    pub const GET_CURRENT_FPS: u16 = 102;
    pub const GET_FPS: u16 = 103;
    pub const GET_HEIGHT: u16 = 104;
    pub const GET_INDEX: u16 = 105;
    pub const GET_KEY_FRAME_INTERVAL: u16 = 106;
    pub const GET_LOOPBACK: u16 = 107;
    pub const GET_MOTION_LEVEL: u16 = 108;
    pub const GET_MOTION_TIMEOUT: u16 = 109;
    pub const GET_MUTED: u16 = 110;
    pub const GET_NAME: u16 = 111;
    pub const GET_QUALITY: u16 = 112;
    pub const GET_WIDTH: u16 = 113;
    pub const TICK: u16 = 150;

    pub const INTERNAL_GET: u16 = 200;
    pub const GET_NAMES: u16 = 201;
}

fn permission() -> CameraPermission {
    camera::backend().map_or(CameraPermission::Refused, |b| b.permission())
}

fn arg<'gc>(args: &[Value<'gc>], i: usize) -> Value<'gc> {
    args.get(i).cloned().unwrap_or(Value::Undefined)
}

pub fn method<'gc>(
    activation: &mut Activation<'_, 'gc>,
    this: Object<'gc>,
    args: &[Value<'gc>],
    index: u16,
) -> Result<Value<'gc>, Error<'gc>> {
    use method::*;
    const CNAME: &str = "Camera";

    match index {
        INTERNAL_GET => return get_camera(activation, this, args),
        GET_NAMES => {
            let names = camera::names();
            let values: Vec<Value<'gc>> =
                names.into_iter().map(|n| AvmString::new_utf8(activation.gc(), n).into()).collect();
            return Ok(ArrayBuilder::new(activation).with(values).into());
        }
        SET_CURSOR => {
            avm1_stub!(activation, CNAME, "setCursor");
            return Ok(Value::Undefined);
        }
        _ => {}
    }

    let NativeObject::Camera(cam) = this.native() else {
        return Ok(Value::Undefined);
    };

    match index {
        SET_MODE => {
            let width = arg(args, 0).coerce_to_i32(activation)?;
            let height = arg(args, 1).coerce_to_i32(activation)?;
            let fps = arg(args, 2).coerce_to_f64(activation)?;
            let mut c = cam.borrow_mut();
            if width > 0 {
                c.width = width;
            }
            if height > 0 {
                c.height = height;
            }
            if fps > 0.0 {
                c.fps = fps;
            }
            c.start();
        }
        SET_QUALITY => {
            let bandwidth = arg(args, 0).coerce_to_i32(activation)?;
            let quality = arg(args, 1).coerce_to_i32(activation)?;
            let mut c = cam.borrow_mut();
            c.bandwidth = bandwidth;
            c.quality = quality;
        }
        SET_KEY_FRAME_INTERVAL => {
            let v = arg(args, 0).coerce_to_i32(activation)?;
            cam.borrow_mut().key_frame_interval = v;
        }
        SET_MOTION_LEVEL => {
            let level = arg(args, 0).coerce_to_i32(activation)?;
            let timeout = match arg(args, 1) {
                Value::Undefined => 2000,
                v => v.coerce_to_i32(activation)?,
            };
            let mut c = cam.borrow_mut();
            c.motion_level = level.clamp(0, 100);
            c.motion_timeout = timeout.max(0);
        }
        SET_LOOPBACK => {
            let v = arg(args, 0).as_bool(activation.swf_version());
            cam.borrow_mut().loopback = v;
        }
        GET_ACTIVITY_LEVEL => return Ok(camera::backend().map_or(-1.0, |b| b.activity_level()).into()),
        GET_CURRENT_FPS => return Ok(camera::backend().map_or(0.0, |b| b.current_fps()).into()),
        GET_BANDWIDTH => return Ok(cam.borrow().bandwidth.into()),
        GET_FPS => return Ok(cam.borrow().fps.into()),
        // The size frames really come in at once they do.
        GET_WIDTH => {
            let w = camera::frame().map_or(0, |f| f.width as i32);
            return Ok(if w > 0 { w } else { cam.borrow().width }.into());
        }
        GET_HEIGHT => {
            let h = camera::frame().map_or(0, |f| f.height as i32);
            return Ok(if h > 0 { h } else { cam.borrow().height }.into());
        }
        GET_INDEX => return Ok((cam.borrow().index as i32).into()),
        GET_KEY_FRAME_INTERVAL => return Ok(cam.borrow().key_frame_interval.into()),
        GET_LOOPBACK => return Ok(cam.borrow().loopback.into()),
        GET_MOTION_LEVEL => return Ok(cam.borrow().motion_level.into()),
        GET_MOTION_TIMEOUT => return Ok(cam.borrow().motion_timeout.into()),
        GET_MUTED => return Ok(cam.borrow().muted.into()),
        GET_NAME => return Ok(AvmString::new_utf8(activation.gc(), cam.borrow().name.clone()).into()),
        GET_QUALITY => return Ok(cam.borrow().quality.into()),
        TICK => tick(activation, this, cam)?,
        _ => {}
    }

    Ok(Value::Undefined)
}

/// `Camera.get(index)`: the camera (the same object each time), or null when there is none.
fn get_camera<'gc>(
    activation: &mut Activation<'_, 'gc>,
    this: Object<'gc>,
    args: &[Value<'gc>],
) -> Result<Value<'gc>, Error<'gc>> {
    let names = camera::names();
    if names.is_empty() {
        return Ok(Value::Null);
    }
    let index = match arg(args, 0) {
        Value::Undefined | Value::Null => 0,
        v => v.coerce_to_i32(activation)?,
    };
    if index < 0 || index as usize >= names.len() {
        return Ok(Value::Null);
    }
    let key = AvmString::new_utf8(activation.gc(), format!("__ruffleCamera{index}"));
    if let Some(Value::Object(existing)) = this.get_local_stored(key, activation) {
        return Ok(existing.into());
    }

    let proto = this.get(istr!("prototype"), activation)?;
    let state = CameraState::new(index as u32, names[index as usize].clone());
    // Flash turns the camera on (and asks) when a movie asks for it.
    state.start();
    let muted = permission() != CameraPermission::Allowed;
    let cam: CameraObject<'gc> = Gc::new(activation.gc(), RefCell::new(CameraState { muted, ..state }));
    let object = Object::new_with_native(activation.strings(), Some(proto), NativeObject::Camera(cam));
    this.define_value(activation.gc(), key, object.into(), Attribute::DONT_ENUM | Attribute::DONT_DELETE);

    // Ten times a second: the permission answer (onStatus) and the motion (onActivity).
    let tick_name = AvmString::new_utf8(activation.gc(), "__ruffleCameraTick");
    activation.context.timers.add_timer(
        TimerCallback::Avm1Method { this: object, method_name: tick_name, params: vec![] },
        100,
        false,
    );
    Ok(object.into())
}

fn tick<'gc>(
    activation: &mut Activation<'_, 'gc>,
    this: Object<'gc>,
    cam: CameraObject<'gc>,
) -> Result<(), Error<'gc>> {
    let p = permission();
    let level = camera::backend().map_or(-1.0, |b| b.activity_level());
    let (status, activity) = {
        let mut c = cam.borrow_mut();
        let mut status = None;
        if p != CameraPermission::Asking && (p != CameraPermission::Allowed) != c.muted {
            c.muted = p != CameraPermission::Allowed;
            status = Some(if c.muted { "Camera.Muted" } else { "Camera.Unmuted" });
        }
        // Motion over motionLevel starts activity; quiet for motionTimeout ends it.
        let mut activity = None;
        if level > c.motion_level as f64 {
            c.quiet_ticks = 0;
            if !c.active {
                c.active = true;
                activity = Some(true);
            }
        } else if c.active {
            c.quiet_ticks += 1;
            if c.quiet_ticks * 100 > c.motion_timeout {
                c.active = false;
                activity = Some(false);
            }
        }
        (status, activity)
    };
    if let Some(code) = status {
        let object_proto = activation.prototypes().object;
        let info = Object::new(activation.strings(), Some(object_proto));
        let code: Value<'gc> = AvmString::new_utf8(activation.gc(), code).into();
        let level: Value<'gc> = AvmString::new_utf8(activation.gc(), "status").into();
        info.set(istr!("code"), code, activation)?;
        info.set(istr!("level"), level, activation)?;
        this.call_method(istr!("onStatus"), &[info.into()], activation, ExecutionReason::Special)?;
    }
    if let Some(on) = activity {
        let name = AvmString::new_utf8(activation.gc(), "onActivity");
        this.call_method(name, &[on.into()], activation, ExecutionReason::Special)?;
    }
    Ok(())
}
