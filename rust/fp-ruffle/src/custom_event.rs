//! Events handed to the player loop from other threads (JNI, async tasks).
//! Based on ruffle-android's custom_event.rs (MIT OR Apache-2.0).

use ruffle_core::events::{KeyDescriptor, MouseButton};

use crate::PlayerRunnable;

pub enum RuffleEvent {
    /// A task is ready to be polled.
    TaskPoll(PlayerRunnable),
    /// A key from FlashARM's touch overlay or controller mapping.
    Key { down: bool, key_descriptor: KeyDescriptor },
    /// Mouse from the overlay's cursor: view coordinates (pixels of the player view).
    MouseMove { x: f64, y: f64 },
    MouseButton { down: bool, x: f64, y: f64, button: MouseButton },
    /// Pause or resume (in-game menu).
    SetPlaying(bool),
    /// Display: scale 0 game default, 1 fit, 2 stretch, 3 fill (crop), 4 original size;
    /// quality 0 low, 1 medium, 2 high, 3 best.
    Display { scale: i32, quality: i32 },
}
