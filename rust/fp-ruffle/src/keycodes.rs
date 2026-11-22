// From ruffle-android (github.com/ruffle-rs/ruffle-android), MIT OR Apache-2.0, Ruffle contributors.
// ARMFlash: split so a bare Android keycode (from the touch overlay) maps the same way.
use android_activity::input::{KeyEvent, Keycode};
use ruffle_core::events::{KeyDescriptor, KeyLocation, LogicalKey, NamedKey, PhysicalKey};

pub fn android_key_event_to_ruffle_key_descriptor(android: &KeyEvent) -> Option<KeyDescriptor> {
    keycode_to_ruffle_key_descriptor(android.key_code())
}

pub fn keycode_to_ruffle_key_descriptor(code: Keycode) -> Option<KeyDescriptor> {
    // ARMFlash: every key needs its own physical key. Ruffle tracks held keys by (physical key,
    // location) and drops a KeyUp whose pair is not down. With every key Unknown, two keys held
    // at once shared one entry: the first release cleared it and the second key's release was
    // thrown away, so that key stayed held in the game forever (Right + Left, Right + X).
    let physical_key = physical_for(code);

    let logical_key = match code {
        Keycode::DpadUp => LogicalKey::Named(NamedKey::ArrowUp),
        Keycode::DpadDown => LogicalKey::Named(NamedKey::ArrowDown),
        Keycode::DpadLeft => LogicalKey::Named(NamedKey::ArrowLeft),
        Keycode::DpadRight => LogicalKey::Named(NamedKey::ArrowRight),
        Keycode::Keycode0 => LogicalKey::Character('0'),
        Keycode::Keycode1 => LogicalKey::Character('1'),
        Keycode::Keycode2 => LogicalKey::Character('2'),
        Keycode::Keycode3 => LogicalKey::Character('3'),
        Keycode::Keycode4 => LogicalKey::Character('4'),
        Keycode::Keycode5 => LogicalKey::Character('5'),
        Keycode::Keycode6 => LogicalKey::Character('6'),
        Keycode::Keycode7 => LogicalKey::Character('7'),
        Keycode::Keycode8 => LogicalKey::Character('8'),
        Keycode::Keycode9 => LogicalKey::Character('9'),
        Keycode::A => LogicalKey::Character('a'),
        Keycode::B => LogicalKey::Character('b'),
        Keycode::C => LogicalKey::Character('c'),
        Keycode::D => LogicalKey::Character('d'),
        Keycode::E => LogicalKey::Character('e'),
        Keycode::F => LogicalKey::Character('f'),
        Keycode::G => LogicalKey::Character('g'),
        Keycode::H => LogicalKey::Character('h'),
        Keycode::I => LogicalKey::Character('i'),
        Keycode::J => LogicalKey::Character('j'),
        Keycode::K => LogicalKey::Character('k'),
        Keycode::L => LogicalKey::Character('l'),
        Keycode::M => LogicalKey::Character('m'),
        Keycode::N => LogicalKey::Character('n'),
        Keycode::O => LogicalKey::Character('o'),
        Keycode::P => LogicalKey::Character('p'),
        Keycode::Q => LogicalKey::Character('q'),
        Keycode::R => LogicalKey::Character('r'),
        Keycode::S => LogicalKey::Character('s'),
        Keycode::T => LogicalKey::Character('t'),
        Keycode::U => LogicalKey::Character('u'),
        Keycode::V => LogicalKey::Character('v'),
        Keycode::W => LogicalKey::Character('w'),
        Keycode::X => LogicalKey::Character('x'),
        Keycode::Y => LogicalKey::Character('y'),
        Keycode::Z => LogicalKey::Character('z'),
        Keycode::Comma => LogicalKey::Character(','),
        Keycode::Period => LogicalKey::Character('.'),
        Keycode::AltLeft => LogicalKey::Named(NamedKey::Alt),
        Keycode::AltRight => LogicalKey::Named(NamedKey::Alt),
        Keycode::ShiftLeft => LogicalKey::Named(NamedKey::Shift),
        Keycode::ShiftRight => LogicalKey::Named(NamedKey::Shift),
        Keycode::Tab => LogicalKey::Named(NamedKey::Tab),
        Keycode::Space => LogicalKey::Character(' '),
        Keycode::Enter => LogicalKey::Named(NamedKey::Enter),
        Keycode::Del => LogicalKey::Named(NamedKey::Backspace),
        Keycode::Grave => LogicalKey::Character('`'),
        Keycode::Minus => LogicalKey::Character('-'),
        Keycode::Equals => LogicalKey::Character('='),
        Keycode::LeftBracket => LogicalKey::Character('['),
        Keycode::RightBracket => LogicalKey::Character(']'),
        Keycode::Backslash => LogicalKey::Character('\\'),
        Keycode::Semicolon => LogicalKey::Character(';'),
        Keycode::Apostrophe => LogicalKey::Character('\''),
        Keycode::Slash => LogicalKey::Character('/'),
        Keycode::Plus => LogicalKey::Character('+'),
        Keycode::PageUp => LogicalKey::Named(NamedKey::PageUp),
        Keycode::PageDown => LogicalKey::Named(NamedKey::PageDown),
        Keycode::Escape => LogicalKey::Named(NamedKey::Escape),
        Keycode::ForwardDel => LogicalKey::Named(NamedKey::Delete),
        Keycode::CtrlLeft => LogicalKey::Named(NamedKey::Control),
        Keycode::CtrlRight => LogicalKey::Named(NamedKey::Control),
        Keycode::CapsLock => LogicalKey::Named(NamedKey::CapsLock),
        Keycode::ScrollLock => LogicalKey::Named(NamedKey::ScrollLock),
        Keycode::Break => LogicalKey::Named(NamedKey::Pause),
        Keycode::MoveHome => LogicalKey::Named(NamedKey::Home),
        Keycode::MoveEnd => LogicalKey::Named(NamedKey::End),
        Keycode::Insert => LogicalKey::Named(NamedKey::Insert),
        Keycode::F1 => LogicalKey::Named(NamedKey::F1),
        Keycode::F2 => LogicalKey::Named(NamedKey::F2),
        Keycode::F3 => LogicalKey::Named(NamedKey::F3),
        Keycode::F4 => LogicalKey::Named(NamedKey::F4),
        Keycode::F5 => LogicalKey::Named(NamedKey::F5),
        Keycode::F6 => LogicalKey::Named(NamedKey::F6),
        Keycode::F7 => LogicalKey::Named(NamedKey::F7),
        Keycode::F8 => LogicalKey::Named(NamedKey::F8),
        Keycode::F9 => LogicalKey::Named(NamedKey::F9),
        Keycode::F10 => LogicalKey::Named(NamedKey::F10),
        Keycode::F11 => LogicalKey::Named(NamedKey::F11),
        Keycode::F12 => LogicalKey::Named(NamedKey::F12),
        Keycode::NumLock => LogicalKey::Named(NamedKey::NumLock),
        Keycode::Numpad0 => LogicalKey::Character('0'),
        Keycode::Numpad1 => LogicalKey::Character('1'),
        Keycode::Numpad2 => LogicalKey::Character('2'),
        Keycode::Numpad3 => LogicalKey::Character('3'),
        Keycode::Numpad4 => LogicalKey::Character('4'),
        Keycode::Numpad5 => LogicalKey::Character('5'),
        Keycode::Numpad6 => LogicalKey::Character('6'),
        Keycode::Numpad7 => LogicalKey::Character('7'),
        Keycode::Numpad8 => LogicalKey::Character('8'),
        Keycode::Numpad9 => LogicalKey::Character('9'),
        Keycode::NumpadDivide => LogicalKey::Character('/'),
        Keycode::NumpadMultiply => LogicalKey::Character('*'),
        Keycode::NumpadSubtract => LogicalKey::Character('-'),
        Keycode::NumpadAdd => LogicalKey::Character('+'),
        Keycode::NumpadDot => LogicalKey::Character('.'),
        Keycode::NumpadComma => LogicalKey::Character(','),
        Keycode::NumpadEnter => LogicalKey::Named(NamedKey::Enter),
        Keycode::NumpadEquals => LogicalKey::Character('='),
        _ => return None,
    };

    let key_location = match code {
        Keycode::AltLeft | Keycode::ShiftLeft | Keycode::CtrlLeft | Keycode::MetaLeft => {
            KeyLocation::Left
        }

        Keycode::AltRight | Keycode::ShiftRight | Keycode::CtrlRight | Keycode::MetaRight => {
            KeyLocation::Right
        }

        Keycode::NumLock
        | Keycode::Numpad0
        | Keycode::Numpad1
        | Keycode::Numpad2
        | Keycode::Numpad3
        | Keycode::Numpad4
        | Keycode::Numpad5
        | Keycode::Numpad6
        | Keycode::Numpad7
        | Keycode::Numpad8
        | Keycode::Numpad9
        | Keycode::NumpadDivide
        | Keycode::NumpadMultiply
        | Keycode::NumpadSubtract
        | Keycode::NumpadAdd
        | Keycode::NumpadDot
        | Keycode::NumpadComma
        | Keycode::NumpadEnter
        | Keycode::NumpadEquals
        | Keycode::NumpadLeftParen
        | Keycode::NumpadRightParen => KeyLocation::Numpad,

        _ => KeyLocation::Standard,
    };

    Some(KeyDescriptor {
        physical_key,
        logical_key,
        key_location,
    })
}

pub fn key_tag_to_key_descriptor(tag: &str) -> Option<KeyDescriptor> {
    match tag {
        "A" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyA,
            logical_key: LogicalKey::Character('a'),
            key_location: KeyLocation::Standard,
        }),
        "B" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyB,
            logical_key: LogicalKey::Character('b'),
            key_location: KeyLocation::Standard,
        }),
        "C" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyC,
            logical_key: LogicalKey::Character('c'),
            key_location: KeyLocation::Standard,
        }),
        "D" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyD,
            logical_key: LogicalKey::Character('d'),
            key_location: KeyLocation::Standard,
        }),
        "E" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyE,
            logical_key: LogicalKey::Character('e'),
            key_location: KeyLocation::Standard,
        }),
        "F" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyF,
            logical_key: LogicalKey::Character('f'),
            key_location: KeyLocation::Standard,
        }),
        "G" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyG,
            logical_key: LogicalKey::Character('g'),
            key_location: KeyLocation::Standard,
        }),
        "H" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyH,
            logical_key: LogicalKey::Character('h'),
            key_location: KeyLocation::Standard,
        }),
        "I" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyI,
            logical_key: LogicalKey::Character('i'),
            key_location: KeyLocation::Standard,
        }),
        "J" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyJ,
            logical_key: LogicalKey::Character('j'),
            key_location: KeyLocation::Standard,
        }),
        "K" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyK,
            logical_key: LogicalKey::Character('k'),
            key_location: KeyLocation::Standard,
        }),
        "L" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyL,
            logical_key: LogicalKey::Character('l'),
            key_location: KeyLocation::Standard,
        }),
        "M" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyM,
            logical_key: LogicalKey::Character('m'),
            key_location: KeyLocation::Standard,
        }),
        "N" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyN,
            logical_key: LogicalKey::Character('n'),
            key_location: KeyLocation::Standard,
        }),
        "O" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyO,
            logical_key: LogicalKey::Character('o'),
            key_location: KeyLocation::Standard,
        }),
        "P" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyP,
            logical_key: LogicalKey::Character('p'),
            key_location: KeyLocation::Standard,
        }),
        "Q" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyQ,
            logical_key: LogicalKey::Character('q'),
            key_location: KeyLocation::Standard,
        }),
        "R" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyR,
            logical_key: LogicalKey::Character('r'),
            key_location: KeyLocation::Standard,
        }),
        "S" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyS,
            logical_key: LogicalKey::Character('s'),
            key_location: KeyLocation::Standard,
        }),
        "T" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyT,
            logical_key: LogicalKey::Character('t'),
            key_location: KeyLocation::Standard,
        }),
        "U" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyU,
            logical_key: LogicalKey::Character('u'),
            key_location: KeyLocation::Standard,
        }),
        "V" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyV,
            logical_key: LogicalKey::Character('v'),
            key_location: KeyLocation::Standard,
        }),
        "W" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyW,
            logical_key: LogicalKey::Character('w'),
            key_location: KeyLocation::Standard,
        }),
        "X" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyX,
            logical_key: LogicalKey::Character('x'),
            key_location: KeyLocation::Standard,
        }),
        "Y" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyY,
            logical_key: LogicalKey::Character('y'),
            key_location: KeyLocation::Standard,
        }),
        "Z" => Some(KeyDescriptor {
            physical_key: PhysicalKey::KeyZ,
            logical_key: LogicalKey::Character('z'),
            key_location: KeyLocation::Standard,
        }),
        "0" => Some(KeyDescriptor {
            physical_key: PhysicalKey::Digit0,
            logical_key: LogicalKey::Character('0'),
            key_location: KeyLocation::Standard,
        }),
        "1" => Some(KeyDescriptor {
            physical_key: PhysicalKey::Digit1,
            logical_key: LogicalKey::Character('1'),
            key_location: KeyLocation::Standard,
        }),
        "2" => Some(KeyDescriptor {
            physical_key: PhysicalKey::Digit2,
            logical_key: LogicalKey::Character('2'),
            key_location: KeyLocation::Standard,
        }),
        "3" => Some(KeyDescriptor {
            physical_key: PhysicalKey::Digit3,
            logical_key: LogicalKey::Character('3'),
            key_location: KeyLocation::Standard,
        }),
        "4" => Some(KeyDescriptor {
            physical_key: PhysicalKey::Digit4,
            logical_key: LogicalKey::Character('4'),
            key_location: KeyLocation::Standard,
        }),
        "5" => Some(KeyDescriptor {
            physical_key: PhysicalKey::Digit5,
            logical_key: LogicalKey::Character('5'),
            key_location: KeyLocation::Standard,
        }),
        "6" => Some(KeyDescriptor {
            physical_key: PhysicalKey::Digit6,
            logical_key: LogicalKey::Character('6'),
            key_location: KeyLocation::Standard,
        }),
        "7" => Some(KeyDescriptor {
            physical_key: PhysicalKey::Digit7,
            logical_key: LogicalKey::Character('7'),
            key_location: KeyLocation::Standard,
        }),
        "8" => Some(KeyDescriptor {
            physical_key: PhysicalKey::Digit8,
            logical_key: LogicalKey::Character('8'),
            key_location: KeyLocation::Standard,
        }),
        "9" => Some(KeyDescriptor {
            physical_key: PhysicalKey::Digit9,
            logical_key: LogicalKey::Character('9'),
            key_location: KeyLocation::Standard,
        }),
        "UP" => Some(KeyDescriptor {
            physical_key: PhysicalKey::ArrowUp,
            logical_key: LogicalKey::Named(NamedKey::ArrowUp),
            key_location: KeyLocation::Standard,
        }),
        "DOWN" => Some(KeyDescriptor {
            physical_key: PhysicalKey::ArrowDown,
            logical_key: LogicalKey::Named(NamedKey::ArrowDown),
            key_location: KeyLocation::Standard,
        }),
        "LEFT" => Some(KeyDescriptor {
            physical_key: PhysicalKey::ArrowLeft,
            logical_key: LogicalKey::Named(NamedKey::ArrowLeft),
            key_location: KeyLocation::Standard,
        }),
        "RIGHT" => Some(KeyDescriptor {
            physical_key: PhysicalKey::ArrowRight,
            logical_key: LogicalKey::Named(NamedKey::ArrowRight),
            key_location: KeyLocation::Standard,
        }),
        "SPACE" => Some(KeyDescriptor {
            physical_key: PhysicalKey::Space,
            logical_key: LogicalKey::Character(' '),
            key_location: KeyLocation::Standard,
        }),
        "ALT" => Some(KeyDescriptor {
            physical_key: PhysicalKey::AltLeft,
            logical_key: LogicalKey::Named(NamedKey::Alt),
            key_location: KeyLocation::Left,
        }),
        "CTRL" => Some(KeyDescriptor {
            physical_key: PhysicalKey::ControlLeft,
            logical_key: LogicalKey::Named(NamedKey::Control),
            key_location: KeyLocation::Left,
        }),
        _ => None,
    }
}

/// The physical key for an Android keycode (US layout positions, as the desktop players use).
fn physical_for(code: Keycode) -> PhysicalKey {
    match code {
        Keycode::DpadUp => PhysicalKey::ArrowUp,
        Keycode::DpadDown => PhysicalKey::ArrowDown,
        Keycode::DpadLeft => PhysicalKey::ArrowLeft,
        Keycode::DpadRight => PhysicalKey::ArrowRight,
        Keycode::Comma => PhysicalKey::Comma,
        Keycode::Period => PhysicalKey::Period,
        Keycode::AltLeft => PhysicalKey::AltLeft,
        Keycode::AltRight => PhysicalKey::AltRight,
        Keycode::ShiftLeft => PhysicalKey::ShiftLeft,
        Keycode::ShiftRight => PhysicalKey::ShiftRight,
        Keycode::Tab => PhysicalKey::Tab,
        Keycode::Space => PhysicalKey::Space,
        Keycode::Enter => PhysicalKey::Enter,
        Keycode::Del => PhysicalKey::Backspace,
        Keycode::Grave => PhysicalKey::Backquote,
        Keycode::Minus => PhysicalKey::Minus,
        Keycode::Equals => PhysicalKey::Equal,
        Keycode::LeftBracket => PhysicalKey::BracketLeft,
        Keycode::RightBracket => PhysicalKey::BracketRight,
        Keycode::Backslash => PhysicalKey::Backslash,
        Keycode::Semicolon => PhysicalKey::Semicolon,
        Keycode::Apostrophe => PhysicalKey::Quote,
        Keycode::Slash => PhysicalKey::Slash,
        Keycode::Plus => PhysicalKey::Equal,
        Keycode::PageUp => PhysicalKey::PageUp,
        Keycode::PageDown => PhysicalKey::PageDown,
        Keycode::Escape => PhysicalKey::Escape,
        Keycode::ForwardDel => PhysicalKey::Delete,
        Keycode::CtrlLeft => PhysicalKey::ControlLeft,
        Keycode::CtrlRight => PhysicalKey::ControlRight,
        Keycode::CapsLock => PhysicalKey::CapsLock,
        Keycode::ScrollLock => PhysicalKey::ScrollLock,
        Keycode::Break => PhysicalKey::Pause,
        Keycode::MoveHome => PhysicalKey::Home,
        Keycode::MoveEnd => PhysicalKey::End,
        Keycode::Insert => PhysicalKey::Insert,
        Keycode::NumLock => PhysicalKey::NumLock,
        Keycode::NumpadDivide => PhysicalKey::NumpadDivide,
        Keycode::NumpadMultiply => PhysicalKey::NumpadMultiply,
        Keycode::NumpadSubtract => PhysicalKey::NumpadSubtract,
        Keycode::NumpadAdd => PhysicalKey::NumpadAdd,
        Keycode::NumpadDot => PhysicalKey::NumpadDecimal,
        Keycode::NumpadComma => PhysicalKey::NumpadComma,
        Keycode::NumpadEnter => PhysicalKey::NumpadEnter,
        Keycode::NumpadEquals => PhysicalKey::Equal,
        Keycode::MetaLeft => PhysicalKey::SuperLeft,
        Keycode::MetaRight => PhysicalKey::SuperRight,
        Keycode::Keycode0 => PhysicalKey::Digit0,
        Keycode::Numpad0 => PhysicalKey::Numpad0,
        Keycode::Keycode1 => PhysicalKey::Digit1,
        Keycode::Numpad1 => PhysicalKey::Numpad1,
        Keycode::Keycode2 => PhysicalKey::Digit2,
        Keycode::Numpad2 => PhysicalKey::Numpad2,
        Keycode::Keycode3 => PhysicalKey::Digit3,
        Keycode::Numpad3 => PhysicalKey::Numpad3,
        Keycode::Keycode4 => PhysicalKey::Digit4,
        Keycode::Numpad4 => PhysicalKey::Numpad4,
        Keycode::Keycode5 => PhysicalKey::Digit5,
        Keycode::Numpad5 => PhysicalKey::Numpad5,
        Keycode::Keycode6 => PhysicalKey::Digit6,
        Keycode::Numpad6 => PhysicalKey::Numpad6,
        Keycode::Keycode7 => PhysicalKey::Digit7,
        Keycode::Numpad7 => PhysicalKey::Numpad7,
        Keycode::Keycode8 => PhysicalKey::Digit8,
        Keycode::Numpad8 => PhysicalKey::Numpad8,
        Keycode::Keycode9 => PhysicalKey::Digit9,
        Keycode::Numpad9 => PhysicalKey::Numpad9,
        Keycode::A => PhysicalKey::KeyA,
        Keycode::B => PhysicalKey::KeyB,
        Keycode::C => PhysicalKey::KeyC,
        Keycode::D => PhysicalKey::KeyD,
        Keycode::E => PhysicalKey::KeyE,
        Keycode::F => PhysicalKey::KeyF,
        Keycode::G => PhysicalKey::KeyG,
        Keycode::H => PhysicalKey::KeyH,
        Keycode::I => PhysicalKey::KeyI,
        Keycode::J => PhysicalKey::KeyJ,
        Keycode::K => PhysicalKey::KeyK,
        Keycode::L => PhysicalKey::KeyL,
        Keycode::M => PhysicalKey::KeyM,
        Keycode::N => PhysicalKey::KeyN,
        Keycode::O => PhysicalKey::KeyO,
        Keycode::P => PhysicalKey::KeyP,
        Keycode::Q => PhysicalKey::KeyQ,
        Keycode::R => PhysicalKey::KeyR,
        Keycode::S => PhysicalKey::KeyS,
        Keycode::T => PhysicalKey::KeyT,
        Keycode::U => PhysicalKey::KeyU,
        Keycode::V => PhysicalKey::KeyV,
        Keycode::W => PhysicalKey::KeyW,
        Keycode::X => PhysicalKey::KeyX,
        Keycode::Y => PhysicalKey::KeyY,
        Keycode::Z => PhysicalKey::KeyZ,
        Keycode::F1 => PhysicalKey::F1,
        Keycode::F2 => PhysicalKey::F2,
        Keycode::F3 => PhysicalKey::F3,
        Keycode::F4 => PhysicalKey::F4,
        Keycode::F5 => PhysicalKey::F5,
        Keycode::F6 => PhysicalKey::F6,
        Keycode::F7 => PhysicalKey::F7,
        Keycode::F8 => PhysicalKey::F8,
        Keycode::F9 => PhysicalKey::F9,
        Keycode::F10 => PhysicalKey::F10,
        Keycode::F11 => PhysicalKey::F11,
        Keycode::F12 => PhysicalKey::F12,
        _ => PhysicalKey::Unknown,
    }
}
