//! libfpruffle.so: ARMFlash's native Flash player.
//!
//! Adapted from ruffle-android (github.com/ruffle-rs/ruffle-android, MIT OR Apache-2.0, Ruffle
//! contributors): the event loop, surface handling, AAudio backend and key mapping are theirs.
//! ARMFlash's changes:
//! - the graphics backend is chosen by the app (Vulkan, OpenGL ES, or auto) instead of GL only;
//! - every network request goes through ARMFlash's in-process game server (the data packs);
//! - the movie loads from its original URL, so relative loads and sitelocks see the real host;
//! - keys and mouse from ARMFlash's touch overlay and controller mapping come in over JNI,
//!   including right clicks and hover; a real mouse's hover is handled too;
//! - no context menu, no external browser.

mod audio;
mod camera;
mod custom_event;
mod driver;
mod framegen;
mod shader;
mod vkcount;
mod java;
mod keycodes;
mod navigator;
mod trace;

use std::any::Any;
use std::rc::Rc;
use std::sync::mpsc::{self, Sender};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};
use std::{panic, thread};

use android_activity::input::{InputEvent, KeyAction, Keycode, MotionAction};
use android_activity::{AndroidApp, AndroidAppWaker, InputStatus, MainEvent, PollEvent};
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JObject};
use jni::sys::{self, jboolean, jdouble, jint, jobject};
use jni::{jni_str, Env, EnvUnowned, JavaVM};
use ruffle_common::duration::FloatDuration;
use ruffle_core::backend::navigator::OwnedFuture;
use ruffle_core::events::{LogicalKey, MouseButton, PlayerEvent};
use ruffle_core::{Player, PlayerBuilder, ViewportDimensions};
use ruffle_frontend_utils::backends::navigator::FutureSpawner;
use ruffle_frontend_utils::backends::storage::DiskStorageBackend;
use ruffle_frontend_utils::content::{ContentDescriptor, PlayingContent};
use ruffle_render_wgpu::backend::WgpuRenderBackend;
use shader::ShaderTarget;
use url::Url;
use wgpu::rwh::{AndroidDisplayHandle, HasWindowHandle, RawDisplayHandle};

use audio::AAudioAudioBackend;
use custom_event::RuffleEvent;
use java::JavaInterface;
use keycodes::{android_key_event_to_ruffle_key_descriptor, keycode_to_ruffle_key_descriptor};
use navigator::GameNavigator;
use trace::FileLogBackend;

#[derive(Copy, Clone, Eq, PartialEq)]
struct PlayerId(i64);

impl PlayerId {
    fn new() -> Self {
        use std::sync::atomic::{AtomicI64, Ordering};
        static NEXT: AtomicI64 = AtomicI64::new(0);
        Self(NEXT.fetch_add(1, Ordering::Relaxed))
    }
}

pub struct PlayerRunnable(async_task::Runnable<PlayerId>);

struct ActivePlayer {
    id: PlayerId,
    player: Arc<Mutex<Player>>,
}

#[derive(Clone)]
pub struct EventSender {
    sender: Sender<RuffleEvent>,
    waker: AndroidAppWaker,
}

impl EventSender {
    pub fn send(&self, event: RuffleEvent) {
        if self.sender.send(event).is_ok() {
            self.waker.wake();
        }
    }
}

struct AndroidExecutor {
    event_loop: EventSender,
    player_id: PlayerId,
}

impl<E: std::error::Error + 'static> FutureSpawner<E> for AndroidExecutor {
    fn spawn(&self, future: OwnedFuture<(), E>) {
        let future = async {
            if let Err(e) = future.await {
                tracing::error!("Async error: {}", e);
            }
        };
        let event_loop = self.event_loop.clone();
        let scheduler = move |task| event_loop.send(RuffleEvent::TaskPoll(PlayerRunnable(task)));
        let (runnable, task) = async_task::Builder::new().metadata(self.player_id).spawn_local(|_| future, scheduler);
        task.detach();
        runnable.schedule();
    }
}

fn backends(choice: &str) -> wgpu::Backends {
    match choice {
        "vulkan" => wgpu::Backends::VULKAN,
        "gl" => wgpu::Backends::GL,
        // Auto: Vulkan where the device has it, OpenGL ES otherwise.
        _ => wgpu::Backends::VULKAN | wgpu::Backends::GL,
    }
}

fn surface_target(window: &ndk::native_window::NativeWindow) -> wgpu::SurfaceTargetUnsafe {
    wgpu::SurfaceTargetUnsafe::RawHandle {
        raw_display_handle: Some(RawDisplayHandle::Android(AndroidDisplayHandle::new())),
        raw_window_handle: window.window_handle().unwrap().into(),
    }
}

#[tokio::main]
async fn run(app: AndroidApp) {
    let mut last_frame_time = Instant::now();
    let mut next_frame_time = Some(Instant::now());
    let mut quit = false;
    let (sender, receiver) = mpsc::channel::<RuffleEvent>();
    let mut native_window: Option<ndk::native_window::NativeWindow> = None;
    let mut playerbox: Option<ActivePlayer> = None;
    let sender = EventSender { sender, waker: app.create_waker() };

    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr() as *mut sys::JavaVM) };
    let activity_raw = app.activity_as_ptr() as jobject;

    struct Settings {
        swf_url: String,
        proxy_url: String,
        backend: String,
        storage: std::path::PathBuf,
        trace: Option<std::path::PathBuf>,
        scale: i32,
        quality: i32,
        vk_gipa: usize,
        sdk: u32,
        shader_preset: String,
        shader_params: String,
        /// The device cameras for webcam games, one name per line (front first).
        cameras: String,
        /// Frame generation's settings (framegen::Settings), and the screen's refresh rate.
        frame_gen: String,
    }
    let settings = vm
        .attach_current_thread(|env| -> jni::errors::Result<_> {
            let activity = unsafe { env.as_cast_raw::<JObject>(&activity_raw) }?;
            let s = Settings {
                swf_url: JavaInterface::get_string(env, &activity, "swfUrl"),
                proxy_url: JavaInterface::get_string(env, &activity, "proxyUrl"),
                backend: JavaInterface::get_string(env, &activity, "backend"),
                storage: JavaInterface::storage_dir(env, &activity),
                trace: Some(JavaInterface::get_string(env, &activity, "traceOutput")).filter(|s| !s.is_empty()).map(Into::into),
                cameras: JavaInterface::get_string(env, &activity, "cameras"),
                scale: JavaInterface::get_string(env, &activity, "scale").parse().unwrap_or(0),
                quality: JavaInterface::get_string(env, &activity, "quality").parse().unwrap_or(2),
                vk_gipa: JavaInterface::get_string(env, &activity, "vkGipa").parse().unwrap_or(0),
                sdk: JavaInterface::get_string(env, &activity, "sdk").parse().unwrap_or(30),
                shader_preset: JavaInterface::get_string(env, &activity, "shaderPreset"),
                shader_params: JavaInterface::get_string(env, &activity, "shaderParams"),
                frame_gen: JavaInterface::get_string(env, &activity, "frameGen"),
            };
            let _ = unsafe { env.set_rust_field(&*activity, jni_str!("eventLoopHandle"), sender.clone()) };
            Ok(s)
        })
        .expect("JNI calls on the main thread must succeed");
    log::info!("swf {} via {} backend {}", settings.swf_url, settings.proxy_url, settings.backend);
    // Webcam games: the device camera, when it has one.
    if !settings.cameras.is_empty() {
        ruffle_core::camera::set_backend(Box::new(camera::AndroidCamera::new(
            app.vm_as_ptr() as *mut jni::sys::JavaVM,
            activity_raw,
            &settings.cameras,
        )));
    }

    // View coordinates (overlay) to surface pixels.
    let to_surface = |app: &AndroidApp, window: &ndk::native_window::NativeWindow, x: f64, y: f64| -> (f64, f64) {
        let view = get_view_size(app).unwrap_or((window.width(), window.height()));
        (
            x * window.width() as f64 / view.0.max(1) as f64,
            y * window.height() as f64 / view.1.max(1) as f64,
        )
    };

    let mut last_mem_check = Instant::now();
    let mut last_mem_log = Instant::now();
    let mut gc_maps = map_count();

    while !quit {
        let mut needs_redraw = false;
        app.poll_events(
            Some(next_frame_time.and_then(|next| next.checked_duration_since(last_frame_time)).unwrap_or_else(|| Duration::from_millis(100))),
            |event| match event {
                PollEvent::Main(event) => match event {
                    MainEvent::Destroy => {
                        if let Some(player) = playerbox.as_ref() {
                            player.player.lock().unwrap().flush_shared_objects();
                        }
                        quit = true;
                    }
                    MainEvent::WindowResized { .. } => {
                        if let (Some(player), Some(window)) = (playerbox.as_ref(), native_window.as_ref()) {
                            let scale = app.config().density().map(|dpi| dpi as f64 / 160.0).unwrap_or(1.0);
                            player.player.lock().unwrap().set_viewport_dimensions(ViewportDimensions {
                                width: window.width() as u32,
                                height: window.height() as u32,
                                scale_factor: scale,
                            });
                            needs_redraw = true;
                        }
                    }
                    MainEvent::Resume { .. } => {
                        if let (Some(player), Some(window)) = (playerbox.as_ref(), native_window.as_ref()) {
                            if window.width() > 0 && window.height() > 0 {
                                let mut player = player.player.lock().unwrap();
                                let renderer = <dyn Any>::downcast_mut::<WgpuRenderBackend<ShaderTarget>>(player.renderer_mut()).unwrap();
                                if let Err(e) = renderer.target().recreate(renderer.descriptors(), window, (window.width() as u32, window.height() as u32)) {
                                    log::warn!("surface: {e}");
                                }
                            }
                        }
                    }
                    MainEvent::InitWindow { .. } => {
                        native_window = app.native_window();
                        let window = native_window.as_ref().expect("native_window after InitWindow");
                        let scale = app.config().density().map(|dpi| dpi as f64 / 160.0).unwrap_or(1.0);
                        let dimensions = ViewportDimensions { width: window.width() as u32, height: window.height() as u32, scale_factor: scale };
                        log::info!("Init window: {} x {}", window.width(), window.height());

                        if let Some(active) = &playerbox {
                            let mut player_lock = active.player.lock().unwrap();
                            let renderer = <dyn Any>::downcast_mut::<WgpuRenderBackend<ShaderTarget>>(player_lock.renderer_mut()).unwrap();
                            if let Err(e) = renderer.target().recreate(renderer.descriptors(), window, (window.width() as u32, window.height() as u32)) {
                                log::warn!("surface: {e}");
                            }
                            // Back from the background: stay paused if the pause menu is open.
                            player_lock.set_is_playing(!MENU_PAUSED.load(std::sync::atomic::Ordering::Relaxed));
                        } else {
                            let wanted = backends(&settings.backend);
                            // Frame generation's device features, asked for before the device is made.
                            let fg_settings = framegen::Settings::parse(&settings.frame_gen);
                            framegen::set_display_rate_from(&settings.frame_gen);
                            framegen::prepare_device(fg_settings.fp16);
                            // A custom Vulkan driver first, when one was loaded.
                            let custom = if settings.vk_gipa != 0 && wanted.contains(wgpu::Backends::VULKAN) {
                                match unsafe {
                                    driver::renderer_with_driver(settings.vk_gipa, surface_target(window), (dimensions.width, dimensions.height), settings.sdk)
                                } {
                                    Ok(r) => Some(r),
                                    Err(e) => {
                                        log::warn!("custom driver could not render ({e}); using the system driver");
                                        None
                                    }
                                }
                            } else {
                                None
                            };
                            let renderer = if let Some(r) = custom { Ok(r) } else { unsafe {
                                shader::renderer_for_window(surface_target(window), (dimensions.width, dimensions.height), wanted)
                            } };
                            // Vulkan refused (no driver, broken driver): fall back to OpenGL ES.
                            let renderer = match renderer {
                                Ok(r) => r,
                                Err(e) if wanted != wgpu::Backends::GL => {
                                    log::warn!("renderer {:?} failed ({e}); using OpenGL ES", wanted);
                                    unsafe {
                                        shader::renderer_for_window(surface_target(window), (dimensions.width, dimensions.height), wgpu::Backends::GL)
                                            .expect("OpenGL ES renderer")
                                    }
                                }
                                Err(e) => panic!("no renderer: {e}"),
                            };
                            { let i = renderer.descriptors().adapter.get_info(); log::info!("renderer: {:?} on {} ({})", i.backend, i.name, i.driver_info); }
                            // Frame generation on this device (Vulkan, and a build that has it).
                            let fg_features = framegen::device_made();
                            renderer.target().frame_gen_attach(renderer.descriptors(), fg_features);
                            renderer.target().update_frame_gen(renderer.descriptors(), Some(window), Some(fg_settings));
                            // A RetroArch shader preset, when one is chosen.
                            if !settings.shader_preset.is_empty() {
                                match renderer.target().set_preset(renderer.descriptors(), Some(&settings.shader_preset)) {
                                    Ok(()) => {
                                        log::info!("shader preset {}", settings.shader_preset);
                                        apply_shader_params(renderer.target(), &settings.shader_params);
                                    }
                                    Err(e) => log::warn!("{e}"),
                                }
                            }

                            let movie_url = Url::parse(&settings.swf_url).unwrap_or_else(|_| Url::parse("file:///movie.swf").unwrap());
                            let player_id = PlayerId::new();
                            let navigator = GameNavigator::new(
                                movie_url.clone(),
                                Url::parse(&settings.proxy_url).ok(),
                                AndroidExecutor { event_loop: sender.clone(), player_id },
                                Rc::new(PlayingContent::DirectFile(ContentDescriptor::new_remote(movie_url.clone()))),
                            );

                            playerbox = Some(ActivePlayer {
                                id: player_id,
                                player: PlayerBuilder::new()
                                    .with_renderer(renderer)
                                    .with_audio(AAudioAudioBackend::new().unwrap_or_else(|e| {
                                        log::warn!("audio stream failed to open ({e}); starting without sound");
                                        AAudioAudioBackend::silent()
                                    }))
                                    .with_storage(Box::new(DiskStorageBackend::new(settings.storage.clone())))
                                    .with_navigator(navigator)
                                    .with_log(FileLogBackend::new(settings.trace.as_deref()))
                                    .with_video(ruffle_video_software::backend::SoftwareVideoBackend::new())
                                    .build(),
                            });

                            let player = &playerbox.as_ref().unwrap().player;
                            let mut player_lock = player.lock().unwrap();
                            player_lock.fetch_root_movie(movie_url.to_string(), Vec::new(), Box::new(|_| {}));
                            player_lock.set_is_playing(true);
                            player_lock.set_letterbox(ruffle_core::config::Letterbox::On);
                            player_lock.set_viewport_dimensions(dimensions);
                            apply_display(&mut player_lock, settings.scale, settings.quality);
                            last_frame_time = Instant::now();
                            next_frame_time = Some(Instant::now());
                            log::info!("MOVIE STARTED");
                        }
                    }
                    MainEvent::TerminateWindow { .. } => {
                        if let Some(p) = playerbox.as_ref() {
                            let mut player = p.player.lock().unwrap();
                            player.set_is_playing(false);
                            // Let the surface go with its window: coming back makes a new one, and
                            // Vulkan refuses a second surface on a window that still has one.
                            if let Some(renderer) = <dyn Any>::downcast_mut::<WgpuRenderBackend<ShaderTarget>>(player.renderer_mut()) {
                                renderer.target().release();
                            }
                        }
                        native_window = None;
                    }
                    MainEvent::InputAvailable => {
                        if let Ok(mut inputs) = app.input_events_iter() {
                            while inputs.next(|input| match input {
                                InputEvent::MotionEvent(event) => {
                                    let Some(window) = native_window.as_ref() else { return InputStatus::Unhandled };
                                    let pointer = event.pointer_at_index(event.pointer_index());
                                    let coords = get_loc_in_window(&app);
                                    let (x, y) = to_surface(&app, window, pointer.x() as f64 - coords.0 as f64, pointer.y() as f64 - coords.1 as f64);
                                    let ruffle_event = match event.action() {
                                        MotionAction::Down | MotionAction::PointerDown | MotionAction::ButtonPress => {
                                            PlayerEvent::MouseDown { x, y, button: MouseButton::Left, index: None }
                                        }
                                        MotionAction::Up | MotionAction::PointerUp | MotionAction::ButtonRelease => {
                                            PlayerEvent::MouseUp { x, y, button: MouseButton::Left }
                                        }
                                        // Move, and a real mouse hovering.
                                        MotionAction::Move | MotionAction::HoverMove => PlayerEvent::MouseMove { x, y },
                                        _ => return InputStatus::Unhandled,
                                    };
                                    if let Some(player) = playerbox.as_ref() {
                                        player.player.lock().unwrap().handle_event(ruffle_event);
                                    }
                                    InputStatus::Handled
                                }
                                InputEvent::KeyEvent(event) => {
                                    let Some(player) = playerbox.as_ref() else { return InputStatus::Unhandled };
                                    let Some(key) = android_key_event_to_ruffle_key_descriptor(event) else { return InputStatus::Unhandled };
                                    let down = match event.action() {
                                        KeyAction::Down => true,
                                        KeyAction::Up => false,
                                        _ => return InputStatus::Unhandled,
                                    };
                                    send_key(&player.player, key, down);
                                    needs_redraw = true;
                                    InputStatus::Handled
                                }
                                _ => InputStatus::Unhandled,
                            }) {}
                        }
                    }
                    _ => {}
                },
                _ => {}
            },
        );

        while let Ok(event) = receiver.try_recv() {
            let Some(player) = playerbox.as_ref() else {
                continue;
            };
            match event {
                RuffleEvent::TaskPoll(task) => {
                    if *task.0.metadata() == player.id {
                        task.0.run();
                    }
                }
                RuffleEvent::Key { down, key_descriptor } => send_key(&player.player, key_descriptor, down),
                RuffleEvent::MouseMove { x, y } => {
                    if let Some(w) = native_window.as_ref() {
                        let (x, y) = to_surface(&app, w, x, y);
                        player.player.lock().unwrap().handle_event(PlayerEvent::MouseMove { x, y });
                    }
                }
                RuffleEvent::MouseButton { down, x, y, button } => {
                    if let Some(w) = native_window.as_ref() {
                        let (x, y) = to_surface(&app, w, x, y);
                        let mut p = player.player.lock().unwrap();
                        p.handle_event(PlayerEvent::MouseMove { x, y });
                        p.handle_event(if down {
                            PlayerEvent::MouseDown { x, y, button, index: None }
                        } else {
                            PlayerEvent::MouseUp { x, y, button }
                        });
                    }
                }
                RuffleEvent::SetPlaying(on) => {
                    MENU_PAUSED.store(!on, std::sync::atomic::Ordering::Relaxed);
                    player.player.lock().unwrap().set_is_playing(on)
                }
                RuffleEvent::Volume(v) => player.player.lock().unwrap().set_volume(v),
                RuffleEvent::Display { scale, quality } => {
                    apply_display(&mut player.player.lock().unwrap(), scale, quality);
                    needs_redraw = true;
                }
                RuffleEvent::ShaderChanged { full } => {
                    let (preset, params) = with_activity(&app, |env, act| {
                        (java::JavaInterface::get_string(env, act, "shaderPreset"), java::JavaInterface::get_string(env, act, "shaderParams"))
                    })
                    .unwrap_or_default();
                    let mut p = player.player.lock().unwrap();
                    if let Some(r) = <dyn Any>::downcast_mut::<WgpuRenderBackend<ShaderTarget>>(p.renderer_mut()) {
                        if full {
                            if let Err(e) = r.target().set_preset(r.descriptors(), Some(preset.as_str()).filter(|s| !s.is_empty())) {
                                log::warn!("{e}");
                            }
                        }
                        apply_shader_params(r.target(), &params);
                    }
                    needs_redraw = true;
                }
                RuffleEvent::FrameGenChanged => {
                    let text = with_activity(&app, |env, act| java::JavaInterface::get_string(env, act, "frameGen")).unwrap_or_default();
                    framegen::set_display_rate_from(&text);
                    let mut p = player.player.lock().unwrap();
                    if let Some(r) = <dyn Any>::downcast_mut::<WgpuRenderBackend<ShaderTarget>>(p.renderer_mut()) {
                        r.target().update_frame_gen(r.descriptors(), native_window.as_ref(), Some(framegen::Settings::parse(&text)));
                    }
                    needs_redraw = true;
                }
            }
        }

        let new_time = Instant::now();
        let dt = new_time.duration_since(last_frame_time).as_micros();
        if dt > 0 {
            last_frame_time = new_time;
            if let Some(player) = playerbox.as_ref() {
                if let Ok(mut player) = player.player.lock() {
                    player.tick(FloatDuration::from_millis(dt as f64 / 1000.0));
                    next_frame_time = Some(new_time + player.time_til_next_frame());
                    needs_redraw = needs_redraw || player.needs_render();
                    let audio = <dyn Any>::downcast_mut::<AAudioAudioBackend>(player.audio_mut()).unwrap();
                    audio.recreate_stream_if_needed();
                }
            } else {
                next_frame_time = None;
            }
        }

        if needs_redraw {
            if let Some(player) = playerbox.as_ref() {
                if let Ok(mut player) = player.player.lock() {
                    // The game's frame rate: frame generation makes no more frames than the
                    // screen can show between two of the game's.
                    let content_rate = player.frame_rate() as f32;
                    framegen::set_content_rate(content_rate);
                    player.render();
                    FRAMES.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    // Let wgpu free what finished GPU work no longer needs, every frame.
                    if let Some(r) = <dyn Any>::downcast_mut::<WgpuRenderBackend<ShaderTarget>>(player.renderer_mut()) {
                        let _ = r.descriptors().device.poll(wgpu::PollType::Poll);
                        // Frame generation lost its window or device: wgpu presents again.
                        if r.target().frame_gen_broken() {
                            r.target().update_frame_gen(r.descriptors(), native_window.as_ref(), None);
                        }
                        // The refresh rate frame generation would like (the app asks Android for it).
                        let vote = if r.target().frame_gen_active() { content_rate * r.target().frame_gen_multiplier() as f32 } else { 0.0 };
                        FG_VOTE.store(vote.to_bits(), std::sync::atomic::Ordering::Relaxed);
                    }
                }
            }
        }

        // Memory guard: when the process's memory mappings have grown by 3,000 since the last
        // full collection, collect everything unreachable now (dropped bitmaps and their GPU
        // textures), and log maps and live bitmaps around it.
        if last_mem_check.elapsed() >= Duration::from_secs(5) {
            last_mem_check = Instant::now();
            let maps = map_count();
            let live = ruffle_core::bitmap::bitmap_data::LIVE_BITMAPS.load(std::sync::atomic::Ordering::Relaxed);
            if maps > gc_maps + 3000 {
                if let Some(player) = playerbox.as_ref() {
                    if let Ok(mut player) = player.player.lock() {
                        player.collect_all_garbage();
                    }
                }
                let after = ruffle_core::bitmap::bitmap_data::LIVE_BITMAPS.load(std::sync::atomic::Ordering::Relaxed);
                log::info!("memory: full collection at {maps} maps, live bitmaps {live} -> {after}");
                gc_maps = maps;
            } else if maps + 3000 < gc_maps {
                gc_maps = maps; // memory went back down: measure from here
            }
            if last_mem_log.elapsed() >= Duration::from_secs(15) {
                last_mem_log = Instant::now();
                // wgpu's live objects, to tell a Ruffle-level leak from one below it.
                let gpu = playerbox.as_ref().and_then(|pl| {
                    let mut p = pl.player.lock().ok()?;
                    let r = <dyn Any>::downcast_mut::<WgpuRenderBackend<ShaderTarget>>(p.renderer_mut())?;
                    let rep = r.descriptors().wgpu_instance.generate_report()?;
                    let h = rep.hub_report();
                    // wgpu-hal's counts: what Vulkan still holds, including objects Ruffle has
                    // dropped that wgpu has not destroyed yet.
                    let c = r.descriptors().device.get_internal_counters().hal;
                    // Self-correction: Vulkan holding far more textures or buffers than Ruffle uses
                    // means destruction is waiting on the GPU; wait for it once, which frees them.
                    if c.textures.read() > h.textures.num_allocated as isize + 300 || c.buffers.read() > h.buffers.num_allocated as isize + 2000 {
                        let _ = r.descriptors().device.poll(wgpu::PollType::Wait { submission_index: None, timeout: None });
                    }
                    Some(format!(
                        "ruffle: buffers {} textures {} bind_groups {}; vulkan: buffers {} textures {} views {} bind_groups {} encoders {} fences {} allocations {} texture MB {} buffer MB {}",
                        h.buffers.num_allocated, h.textures.num_allocated, h.bind_groups.num_allocated,
                        c.buffers.read(), c.textures.read(), c.texture_views.read(), c.bind_groups.read(),
                        c.command_encoders.read(), c.fences.read(), c.memory_allocations.read(),
                        c.texture_memory.read() >> 20, c.buffer_memory.read() >> 20,
                    ))
                }).unwrap_or_default();
                log::info!("memory: {maps} maps, {live} live bitmaps, {gpu}");
                log::info!("memory sizes: {}", map_sizes());
                if vkcount::active() {
                    log::info!("memory vulkan live: {}", vkcount::summary());
                }
            }
        }
    }

    let _ = vm.attach_current_thread(|env| -> jni::errors::Result<()> {
        let activity = unsafe { env.as_cast_raw::<JObject>(&activity_raw) }?;
        let _: jni::errors::Result<EventSender> = unsafe { env.take_rust_field(&*activity, jni_str!("eventLoopHandle")) };
        Ok(())
    });
}

/// Scale mode and quality, from the in-game menu (or the game's saved choice at start).
fn apply_display(p: &mut Player, scale: i32, quality: i32) {
    use ruffle_render::quality::StageQuality;
    use ruffle_core::StageScaleMode;
    let (mode, force) = match scale {
        1 => (StageScaleMode::ShowAll, true),
        2 => (StageScaleMode::ExactFit, true),
        3 => (StageScaleMode::NoBorder, true),
        4 => (StageScaleMode::NoScale, true),
        _ => (StageScaleMode::ShowAll, false), // the game decides
    };
    p.set_forced_scale_mode(false);
    p.set_scale_mode(mode);
    p.set_forced_scale_mode(force);
    p.set_letterbox(if scale == 2 || scale == 3 { ruffle_core::config::Letterbox::Off } else { ruffle_core::config::Letterbox::On });
    p.set_quality(match quality {
        0 => StageQuality::Low,
        1 => StageQuality::Medium,
        3 => StageQuality::Best,
        _ => StageQuality::High,
    });
}

fn send_key(player: &Arc<Mutex<Player>>, key: ruffle_core::events::KeyDescriptor, down: bool) {
    let mut p = player.lock().unwrap();
    p.handle_event(if down { PlayerEvent::KeyDown { key } } else { PlayerEvent::KeyUp { key } });
    if down {
        if let LogicalKey::Character(c) = key.logical_key {
            p.handle_event(PlayerEvent::TextInput { codepoint: c });
        }
    }
}

fn with_sender(env: &mut Env, this: &JObject, f: impl FnOnce(&EventSender)) -> jni::errors::Result<()> {
    let sender: MutexGuard<EventSender> = unsafe { env.get_rust_field(this, jni_str!("eventLoopHandle")) }?;
    f(&sender);
    Ok(())
}

/// A key from the overlay or controller mapping: an Android keycode.
#[no_mangle]
pub unsafe extern "C" fn Java_com_armsx_flashpoint_game_RuffleActivity_nativeKey<'local>(
    mut env: EnvUnowned<'local>,
    this: JObject<'local>,
    code: jint,
    down: jboolean,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
        if let Some(key_descriptor) = keycode_to_ruffle_key_descriptor(Keycode::from(code as u32)) {
            with_sender(env, &this, |s| s.send(RuffleEvent::Key { down: down, key_descriptor }))?;
        }
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Mouse from the overlay cursor, in view pixels. button: 0 move only, 1 left, 2 right.
#[no_mangle]
pub unsafe extern "C" fn Java_com_armsx_flashpoint_game_RuffleActivity_nativeMouse<'local>(
    mut env: EnvUnowned<'local>,
    this: JObject<'local>,
    x: jdouble,
    y: jdouble,
    button: jint,
    down: jboolean,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
        let ev = match button {
            1 => RuffleEvent::MouseButton { down, x, y, button: MouseButton::Left },
            2 => RuffleEvent::MouseButton { down, x, y, button: MouseButton::Right },
            _ => RuffleEvent::MouseMove { x, y },
        };
        with_sender(env, &this, |s| s.send(ev))
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_armsx_flashpoint_game_RuffleActivity_nativeDisplay<'local>(
    mut env: EnvUnowned<'local>,
    this: JObject<'local>,
    scale: jint,
    quality: jint,
) {
    env.with_env(|env| -> jni::errors::Result<()> { with_sender(env, &this, |s| s.send(RuffleEvent::Display { scale, quality })) })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_armsx_flashpoint_game_RuffleActivity_nativeSetPlaying<'local>(
    mut env: EnvUnowned<'local>,
    this: JObject<'local>,
    playing: jboolean,
) {
    env.with_env(|env| -> jni::errors::Result<()> { with_sender(env, &this, |s| s.send(RuffleEvent::SetPlaying(playing))) })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Frame generation's settings changed in the pause menu.
#[no_mangle]
pub unsafe extern "C" fn Java_com_armsx_flashpoint_game_RuffleActivity_nativeFrameGenChanged<'local>(
    mut env: EnvUnowned<'local>,
    this: JObject<'local>,
) {
    env.with_env(|env| -> jni::errors::Result<()> { with_sender(env, &this, |s| s.send(RuffleEvent::FrameGenChanged)) })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Why frame generation cannot run (0 when it can; see framegen::reason).
#[no_mangle]
pub unsafe extern "C" fn Java_com_armsx_flashpoint_game_RuffleActivity_nativeFrameGenReason<'local>(
    _env: EnvUnowned<'local>,
    _this: JObject<'local>,
) -> jint {
    framegen::reason()
}

/// The performance overlay's frame generation line ("" while it is off).
#[no_mangle]
pub unsafe extern "C" fn Java_com_armsx_flashpoint_game_RuffleActivity_nativeFrameGenStatus<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
) -> jni::sys::jstring {
    env.with_env(|env| -> jni::errors::Result<jni::sys::jstring> { Ok(env.new_string(framegen::status())?.into_raw()) })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// The screen's refresh rate now ([display_hz]); returns the rate frame generation would like
/// the screen to run at (the game's rate times the multiplier), 0 while it is off.
#[no_mangle]
pub unsafe extern "C" fn Java_com_armsx_flashpoint_game_RuffleActivity_nativeFrameGenTick<'local>(
    _env: EnvUnowned<'local>,
    _this: JObject<'local>,
    display_hz: jni::sys::jfloat,
) -> jni::sys::jfloat {
    framegen::set_display_rate(display_hz);
    f32::from_bits(FG_VOTE.load(std::sync::atomic::Ordering::Relaxed))
}

/// What nativeFrameGenTick returns (f32 bits), written by the player loop.
static FG_VOTE: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);

/// Frames drawn so far, for the performance overlay's FPS (the overlay samples the difference).
static FRAMES: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

#[no_mangle]
pub unsafe extern "C" fn Java_com_armsx_flashpoint_game_RuffleActivity_nativeFrames<'local>(
    _env: EnvUnowned<'local>,
    _this: JObject<'local>,
) -> jni::sys::jlong {
    FRAMES.load(std::sync::atomic::Ordering::Relaxed) as jni::sys::jlong
}

/// The commonest mapping sizes for the GPU driver's memory (kgsl), large mallocs (Scudo
/// secondary) and plain anonymous memory, to tell what the mappings are.
fn map_sizes() -> String {
    let Ok(text) = std::fs::read_to_string("/proc/self/maps") else { return String::new() };
    let mut kinds: std::collections::BTreeMap<&str, std::collections::HashMap<u64, u32>> = Default::default();
    for line in text.lines() {
        let mut it = line.split_whitespace();
        let Some(range) = it.next() else { continue };
        let name = line.splitn(6, ' ').nth(5).unwrap_or("").trim();
        let kind = if name.contains("kgsl-3d0") { "kgsl" } else if name.contains("scudo:secondary") { "scudo2" } else if name.is_empty() { "anon" } else { continue };
        let (a, b) = range.split_once('-').unwrap_or(("0", "0"));
        let size = u64::from_str_radix(b, 16).unwrap_or(0).saturating_sub(u64::from_str_radix(a, 16).unwrap_or(0));
        *kinds.entry(kind).or_default().entry(size).or_default() += 1;
    }
    kinds.iter().map(|(k, m)| {
        let mut v: Vec<_> = m.iter().collect();
        v.sort_by(|a, b| b.1.cmp(a.1));
        let top: Vec<String> = v.iter().take(5).map(|(s, n)| format!("{}KB x{}", *s / 1024, n)).collect();
        format!("{k}[{}]", top.join(" "))
    }).collect::<Vec<_>>().join(" ")
}

/// Lines in /proc/self/maps: the process's memory mappings (Android caps them at 65,530).
fn map_count() -> usize {
    std::fs::read("/proc/self/maps").map(|b| b.iter().filter(|&&c| c == b'\n').count()).unwrap_or(0)
}

/// The pause menu (or the touch layout editor) has the game paused; a new window after the app
/// was in the background must not start it again.
static MENU_PAUSED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// Times Ruffle reported trouble running this game: an error from the player (a script error,
/// a file the movie could not load, a failed loader) or a panic. The pause menu suggests Flash
/// Player once this is above zero. Stubs (features Ruffle has not written yet) are not counted:
/// most games hit some harmless ones.
static TROUBLE: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

struct TroubleLogger(android_logger::AndroidLogger);

impl log::Log for TroubleLogger {
    fn enabled(&self, m: &log::Metadata) -> bool {
        self.0.enabled(m)
    }
    fn log(&self, r: &log::Record) {
        let t = r.target();
        if r.level() == log::Level::Error && (t.starts_with("ruffle") || t == "panic") {
            TROUBLE.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        }
        self.0.log(r)
    }
    fn flush(&self) {
        self.0.flush()
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_armsx_flashpoint_game_RuffleActivity_nativeTrouble<'local>(
    _env: EnvUnowned<'local>,
    _this: JObject<'local>,
) -> jni::sys::jlong {
    TROUBLE.load(std::sync::atomic::Ordering::Relaxed) as jni::sys::jlong
}

/// Shader parameters from Kotlin: one "name value" pair per line.
fn apply_shader_params(target: &ShaderTarget, params: &str) {
    for line in params.lines() {
        let mut it = line.split_whitespace();
        if let (Some(name), Some(v)) = (it.next(), it.next().and_then(|v| v.parse::<f32>().ok())) {
            target.set_parameter(name, v);
        }
    }
}

/// The pause menu or Config changed the shader: reload the preset (`full`) or its parameters.
#[no_mangle]
pub unsafe extern "C" fn Java_com_armsx_flashpoint_game_RuffleActivity_nativeShaderChanged<'local>(
    mut env: EnvUnowned<'local>,
    this: JObject<'local>,
    full: jboolean,
) {
    env.with_env(|env| -> jni::errors::Result<()> { with_sender(env, &this, |s| s.send(RuffleEvent::ShaderChanged { full: full })) })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// The game's volume, 0.0 to 1.0 (the pause menu's Game volume).
#[no_mangle]
pub unsafe extern "C" fn Java_com_armsx_flashpoint_game_RuffleActivity_nativeVolume<'local>(
    mut env: EnvUnowned<'local>,
    this: JObject<'local>,
    volume: jni::sys::jfloat,
) {
    env.with_env(|env| -> jni::errors::Result<()> { with_sender(env, &this, |s| s.send(RuffleEvent::Volume(volume))) })
        .resolve::<ThrowRuntimeExAndDefault>()
}

pub fn with_activity<T>(app: &AndroidApp, f: impl FnOnce(&mut Env, &JObject) -> T) -> jni::errors::Result<T> {
    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) };
    let activity_raw = app.activity_as_ptr() as jobject;
    vm.attach_current_thread(|env| {
        let activity = unsafe { env.as_cast_raw::<JObject>(&activity_raw) }?;
        Ok(f(env, &activity))
    })
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_armsx_flashpoint_game_RuffleActivity_nativeInit<'local>(
    mut env: EnvUnowned<'local>,
    class: JClass<'local>,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
        // Android's log, plus a count of the trouble Ruffle reports (see TROUBLE).
        let inner = android_logger::AndroidLogger::new(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("fpruffle")
                .with_filter(android_logger::FilterBuilder::new().parse("warn,fpruffle=info,ruffle=info").build()),
        );
        if log::set_boxed_logger(Box::new(TroubleLogger(inner))).is_ok() {
            log::set_max_level(log::LevelFilter::Info);
        }
        panic::set_hook(Box::new(|info| {
            let thread = thread::current();
            log::error!(target: "panic", "thread '{}' {}\n{:?}", thread.name().unwrap_or("?"), info, backtrace::Backtrace::new());
        }));
        JavaInterface::init(env, &class);
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

fn get_loc_in_window(app: &AndroidApp) -> (i32, i32) {
    with_activity(app, JavaInterface::get_loc_in_window).unwrap_or((0, 0))
}

fn get_view_size(app: &AndroidApp) -> Result<(i32, i32), Box<dyn std::error::Error>> {
    Ok(with_activity(app, |env, activity| (JavaInterface::get_surface_width(env, activity), JavaInterface::get_surface_height(env, activity)))?)
}

#[no_mangle]
fn android_main(app: AndroidApp) {
    let _ = rustls::crypto::ring::default_provider().install_default();
    run(app);
}
