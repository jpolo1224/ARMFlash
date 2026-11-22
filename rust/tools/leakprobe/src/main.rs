//! leakprobe <swf> [frames]: run a movie offscreen with Ruffle's wgpu renderer, clicking and
//! dragging across the stage, and print wgpu's live object counts every 300 frames.
use ruffle_core::events::{MouseButton, PlayerEvent};
use ruffle_core::tag_utils::movie_from_path;
use ruffle_core::PlayerBuilder;
use ruffle_render_wgpu::backend::{create_wgpu_instance, request_adapter_and_device, WgpuRenderBackend};
use ruffle_render_wgpu::descriptors::Descriptors;
use ruffle_render_wgpu::target::TextureTarget;
use ruffle_render_wgpu::wgpu;
use std::sync::Arc;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let frames: u32 = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(3000);
    let instance = create_wgpu_instance(wgpu::Backends::PRIMARY, wgpu::BackendOptions::default(), None);
    let (adapter, device, queue) =
        futures::executor::block_on(request_adapter_and_device(wgpu::Backends::PRIMARY, &instance, None, wgpu::PowerPreference::HighPerformance)).expect("device");
    let descriptors = Arc::new(Descriptors::new(instance, adapter, device, queue));
    // The movie under its real URL (MOVIE_URL), so site-locked games run; else its file path.
    let movie = match std::env::var("MOVIE_URL") {
        Ok(url) => ruffle_core::tag_utils::SwfMovieData::from_data(&std::fs::read(&args[1]).expect("read"), url, None, None).expect("movie"),
        Err(_) => movie_from_path(&args[1], None).expect("movie"),
    };
    let (w, h) = (movie.width().to_pixels() as u32, movie.height().to_pixels() as u32);
    let target = TextureTarget::new(&descriptors.device, (w, h)).expect("target");
    let player = PlayerBuilder::new()
        .with_renderer(WgpuRenderBackend::new(descriptors.clone(), target).expect("renderer"))
        .with_movie(movie)
        .with_viewport_dimensions(w, h, 1.0)
        .build();
    let clicks: Vec<(u32, f64, f64)> = std::env::var("CLICKS").unwrap_or_default().split(';').filter_map(|c| {
        let v: Vec<&str> = c.split(',').collect();
        Some((v.first()?.trim().parse().ok()?, v.get(1)?.trim().parse().ok()?, v.get(2)?.trim().parse().ok()?))
    }).collect();
    let random_from: u32 = std::env::var("RANDOM_FROM").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let mut seed = 12345u64;
    let mut rnd = |n: f64| { seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407); ((seed >> 33) as f64 / (1u64 << 31) as f64) * n };
    for f in 0..frames {
        {
            let mut p = player.lock().unwrap();
            // CLICKS="frame,x,y;...": clicks at exact frames (to get through menus); random
            // clicking only from RANDOM_FROM on.
            for c in clicks.iter().filter(|c| c.0 == f) {
                p.handle_event(PlayerEvent::MouseMove { x: c.1, y: c.2 });
                p.handle_event(PlayerEvent::MouseDown { x: c.1, y: c.2, button: MouseButton::Left, index: None });
                p.handle_event(PlayerEvent::MouseUp { x: c.1, y: c.2, button: MouseButton::Left });
            }
            if f < random_from {
            } else if f % 15 == 0 {
                let (x, y) = (rnd(w as f64), rnd(h as f64));
                p.handle_event(PlayerEvent::MouseMove { x, y });
                p.handle_event(PlayerEvent::MouseDown { x, y, button: MouseButton::Left, index: None });
            } else if f % 15 < 10 {
                let (x, y) = (rnd(w as f64), rnd(h as f64));
                p.handle_event(PlayerEvent::MouseMove { x, y });
            } else if f % 15 == 10 {
                p.handle_event(PlayerEvent::MouseUp { x: rnd(w as f64), y: rnd(h as f64), button: MouseButton::Left });
            }
            p.run_frame();
            p.render();
        }
        let _ = descriptors.device.poll(wgpu::PollType::Poll);
        // A screenshot every SHOT_EVERY frames (env, 0 = none) into ./shots, to see what state the game is in.
        let every: u32 = std::env::var("SHOT_EVERY").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
        if every > 0 && f % every == 0 {
            let mut p = player.lock().unwrap();
            if let Some(r) = <dyn std::any::Any>::downcast_ref::<WgpuRenderBackend<TextureTarget>>(p.renderer_mut()) {
                if let Some(img) = r.capture_frame() {
                    let _ = std::fs::create_dir_all("shots");
                    let _ = img.save(format!("shots/{f:06}.png"));
                }
            }
        }
        if f % 300 == 0 {
            if let Some(r) = descriptors.wgpu_instance.generate_report() {
                let hub = r.hub_report();
                let live = |x: &wgpu::wgc::registry::RegistryReport| x.num_allocated;
                println!(
                    "frame {f}: buffers {} textures {} views {} bind_groups {} samplers {} encoders {} cmdbufs {}",
                    live(&hub.buffers), live(&hub.textures), live(&hub.texture_views), live(&hub.bind_groups),
                    live(&hub.samplers), live(&hub.command_encoders), live(&hub.command_buffers),
                );
            }
        }
    }
}
