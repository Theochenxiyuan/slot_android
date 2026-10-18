// From BrandonKowalski/slot 34ac38031fd903eb2a0a0157f1dea4d3a5546195.
// GPL-3.0-or-later; see THIRD_PARTY.md.
use std::sync::OnceLock;

use crate::{cart::*, silhouette::*, Finish, Shell};

#[derive(Copy, Clone, PartialEq, Eq, Debug)]
pub enum Mould {
    Gba,
    Notched,
    Rounded,
}

const GBA_SHELL_PNG: &[u8] = include_bytes!("../assets/gba_neutral.png");
const GB_SHELL_PNG: &[u8] = include_bytes!("../assets/gb_neutral.png");
const GBC_SHELL_PNG: &[u8] = include_bytes!("../assets/gbc_neutral.png");
const GBA_CLEAR_PNG: &[u8] = include_bytes!("../assets/gba_clear.png");
const GB_CLEAR_PNG: &[u8] = include_bytes!("../assets/gb_neutral.png");
const GBC_CLEAR_PNG: &[u8] = include_bytes!("../assets/gbc_clear.png");
const GBA_INTERIOR_PNG: &[u8] = include_bytes!("../assets/gba_interior.png");
const GB_INTERIOR_PNG: &[u8] = include_bytes!("../assets/gb_interior.png");
const GBC_INTERIOR_PNG: &[u8] = include_bytes!("../assets/gbc_interior.png");
const BOARD_PNG: &[u8] = include_bytes!("../assets/board.png");
const GB_BOARD_PNG: &[u8] = include_bytes!("../assets/gb_board.png");

pub fn decode_and_bleed(bytes: &[u8], expected_w: u32, expected_h: u32) -> Vec<u8> {
    let mut dec = png::Decoder::new(std::io::Cursor::new(bytes));
    dec.set_transformations(png::Transformations::normalize_to_color8());
    let mut reader = dec.read_info().expect("png header");
    let mut buf = vec![0u8; reader.output_buffer_size()];
    let info = reader.next_frame(&mut buf).expect("png frame");
    assert_eq!(
        (info.width, info.height),
        (expected_w, expected_h),
        "unexpected png dimensions"
    );
    let src = &buf[..info.buffer_size()];
    let n = (info.width * info.height) as usize;
    let mut rgba = vec![255u8; n * 4];
    match info.color_type {
        png::ColorType::Rgba => rgba.copy_from_slice(src),
        png::ColorType::Rgb => {
            for i in 0..n {
                rgba[i * 4..i * 4 + 3].copy_from_slice(&src[i * 3..i * 3 + 3]);
            }
        }
        other => panic!("unsupported png color type: {other:?}"),
    }
    bleed(&mut rgba, expected_w, expected_h);
    rgba
}

/// Copy an opaque neighbour's colour into each transparent pixel, leaving its alpha at 0.
pub fn bleed(rgba: &mut [u8], w: u32, h: u32) {
    let src = rgba.to_vec();
    let (w, h) = (w as i32, h as i32);
    for y in 0..h {
        for x in 0..w {
            let i = ((y * w + x) * 4) as usize;
            if src[i + 3] != 0 {
                continue;
            }
            for (dx, dy) in [(1, 0), (-1, 0), (0, 1), (0, -1)] {
                let (nx, ny) = (x + dx, y + dy);
                if nx < 0 || ny < 0 || nx >= w || ny >= h {
                    continue;
                }
                let n = ((ny * w + nx) * 4) as usize;
                if src[n + 3] == 0 {
                    continue;
                }
                rgba[i..i + 3].copy_from_slice(&src[n..n + 3]);
                break;
            }
        }
    }
}

pub fn runtime_png_sources() -> [(&'static str, &'static [u8], u32, u32); 10] {
    [
        ("gba_neutral.png", GBA_SHELL_PNG, CART_W, CART_H),
        ("gb_neutral.png", GB_SHELL_PNG, GB_CART_W, GB_CART_H),
        ("gbc_neutral.png", GBC_SHELL_PNG, GB_CART_W, GB_CART_H),
        ("gba_clear.png", GBA_CLEAR_PNG, CART_W, CART_H),
        ("gbc_clear.png", GBC_CLEAR_PNG, GB_CART_W, GB_CART_H),
        ("gba_interior.png", GBA_INTERIOR_PNG, CART_W, CART_H),
        ("gb_interior.png", GB_INTERIOR_PNG, GB_CART_W, GB_CART_H),
        ("gbc_interior.png", GBC_INTERIOR_PNG, GB_CART_W, GB_CART_H),
        ("board.png", BOARD_PNG, CART_W, CART_H),
        ("gb_board.png", GB_BOARD_PNG, GB_CART_W, GB_CART_H),
    ]
}

fn shell_asset(mould: Mould, clear: bool) -> &'static [u8] {
    static GBA: OnceLock<Vec<u8>> = OnceLock::new();
    static GB: OnceLock<Vec<u8>> = OnceLock::new();
    static GBC: OnceLock<Vec<u8>> = OnceLock::new();
    static GBA_CLEAR: OnceLock<Vec<u8>> = OnceLock::new();
    static GB_CLEAR: OnceLock<Vec<u8>> = OnceLock::new();
    static GBC_CLEAR: OnceLock<Vec<u8>> = OnceLock::new();
    let (bytes, w, h, slot) = match (mould, clear) {
        (Mould::Gba, false) => (GBA_SHELL_PNG, CART_W, CART_H, &GBA),
        (Mould::Notched, false) => (GB_SHELL_PNG, GB_CART_W, GB_CART_H, &GB),
        (Mould::Rounded, false) => (GBC_SHELL_PNG, GB_CART_W, GB_CART_H, &GBC),
        (Mould::Gba, true) => (GBA_CLEAR_PNG, CART_W, CART_H, &GBA_CLEAR),
        (Mould::Notched, true) => (GB_CLEAR_PNG, GB_CART_W, GB_CART_H, &GB_CLEAR),
        (Mould::Rounded, true) => (GBC_CLEAR_PNG, GB_CART_W, GB_CART_H, &GBC_CLEAR),
    };
    slot.get_or_init(|| decode_and_bleed(bytes, w, h))
}

fn interior_asset(mould: Mould) -> &'static [u8] {
    static GBA: OnceLock<Vec<u8>> = OnceLock::new();
    static GB: OnceLock<Vec<u8>> = OnceLock::new();
    static GBC: OnceLock<Vec<u8>> = OnceLock::new();
    let (bytes, w, h, slot) = match mould {
        Mould::Gba => (GBA_INTERIOR_PNG, CART_W, CART_H, &GBA),
        Mould::Notched => (GB_INTERIOR_PNG, GB_CART_W, GB_CART_H, &GB),
        Mould::Rounded => (GBC_INTERIOR_PNG, GB_CART_W, GB_CART_H, &GBC),
    };
    slot.get_or_init(|| decode_and_bleed(bytes, w, h))
}

fn board_asset(gba: bool) -> &'static [u8] {
    static GBA: OnceLock<Vec<u8>> = OnceLock::new();
    static GB: OnceLock<Vec<u8>> = OnceLock::new();
    if gba {
        GBA.get_or_init(|| decode_and_bleed(BOARD_PNG, CART_W, CART_H))
    } else {
        GB.get_or_init(|| decode_and_bleed(GB_BOARD_PNG, GB_CART_W, GB_CART_H))
    }
}

struct Spec {
    w: u32,
    h: u32,
    label: (u32, u32, u32, u32),
    mask: &'static [u8],
    mould: Mould,
}

fn spec(shape: usize) -> Spec {
    match shape {
        0 => {
            let (x0, y0, x1, y1) = label_panel(CART_W, CART_H);
            Spec {
                w: CART_W,
                h: CART_H,
                label: (x0, y0, x1 - x0, y1 - y0),
                mask: cart_mask(),
                mould: Mould::Gba,
            }
        }
        1 => {
            let (x0, y0, x1, y1) = gb_label_panel(GB_CART_W, GB_CART_H);
            Spec {
                w: GB_CART_W,
                h: GB_CART_H,
                label: (x0, y0, x1 - x0, y1 - y0),
                mask: gb_cart_mask(GbShell::Notched),
                mould: Mould::Notched,
            }
        }
        _ => Spec {
            w: GB_CART_W,
            h: GB_CART_H,
            label: (GBC_LABEL_X, GBC_LABEL_Y, GBC_LABEL_W, GBC_LABEL_H),
            mask: gb_cart_mask(GbShell::Rounded),
            mould: Mould::Rounded,
        },
    }
}

const CLEAR_ALPHA: u8 = 204;
const NEUTRAL: u8 = 128;

fn overlay(base: u8, colour: u8) -> u8 {
    let (base, colour) = (i32::from(base), i32::from(colour));
    let out = if base >= i32::from(NEUTRAL) {
        colour + ((255 - colour) * (base - i32::from(NEUTRAL)) + 63) / 127
    } else {
        colour - (colour * (i32::from(NEUTRAL) - base) + 64) / i32::from(NEUTRAL)
    };
    out.clamp(0, 255) as u8
}

fn shell_face(s: &Spec, shell: &Shell) -> CartFace {
    let clear = shell.finish != Finish::Solid;
    let src = shell_asset(s.mould, clear);
    let mut rgba = src.to_vec();
    let (lx, ly, lw, lh) = s.label;
    for y in 0..s.h {
        for x in 0..s.w {
            let on_label = (lx..lx + lw).contains(&x) && (ly..ly + lh).contains(&y);
            let i = ((y * s.w + x) * 4) as usize;
            if !on_label {
                for c in 0..3 {
                    rgba[i + c] = overlay(rgba[i + c], shell.colour[c]);
                }
            }
            let own = u32::from(rgba[i + 3]);
            rgba[i + 3] = if on_label {
                255
            } else if clear {
                ((own * u32::from(CLEAR_ALPHA) + 127) / 255) as u8
            } else {
                own as u8
            };
        }
    }
    if shell.finish == Finish::Glitter {
        for y in 0..s.h {
            for x in 0..s.w {
                if !fleck(x, y) {
                    continue;
                }
                let i = ((y * s.w + x) * 4) as usize;
                if rgba[i + 3] == 0 {
                    continue;
                }
                let body = [rgba[i], rgba[i + 1], rgba[i + 2]];
                rgba[i..i + 3].copy_from_slice(&lerp(body, [0xff; 3], 100, 255));
                rgba[i + 3] = 255;
            }
        }
    }
    CartFace {
        rgba,
        w: s.w,
        h: s.h,
    }
}

fn fleck(x: u32, y: u32) -> bool {
    let mut h = x.wrapping_mul(0x9e37_79b9) ^ y.wrapping_mul(0x85eb_ca6b);
    h ^= h >> 15;
    h = h.wrapping_mul(0x2c1b_3c6d);
    h ^= h >> 12;
    h.is_multiple_of(100)
}

fn lerp(a: [u8; 3], b: [u8; 3], num: u32, den: u32) -> [u8; 3] {
    let mut out = [0u8; 3];
    for c in 0..3 {
        out[c] = ((a[c] as u32 * (den - num) + b[c] as u32 * num) / den) as u8;
    }
    out
}

fn paste_label(s: &Spec, face: &mut CartFace, label: &[u8]) {
    let (lx, ly, lw, lh) = s.label;
    for y in 0..lh {
        for x in 0..lw {
            let src = ((y * lw + x) * 4) as usize;
            let a = label[src + 3] as u32;
            if a == 0 {
                continue;
            }
            let d = (((y + ly) * s.w + x + lx) * 4) as usize;
            for c in 0..3 {
                face.rgba[d + c] =
                    ((label[src + c] as u32 * a + face.rgba[d + c] as u32 * (255 - a) + 127) / 255)
                        as u8;
            }
        }
    }
}

fn clip_to_silhouette(s: &Spec, face: &mut CartFace) {
    for (px, cover) in face.rgba.chunks_exact_mut(4).zip(s.mask) {
        px[3] = ((px[3] as u32 * *cover as u32 + 127) / 255) as u8;
    }
}

pub fn reference(shape: usize, colour: [u8; 3], finish: Finish) -> CartFace {
    let s = spec(shape);
    let shell = Shell { colour, finish };
    let mut face = shell_face(&s, &shell);
    // Exercise the original label compositor with a transparent label.
    paste_label(&s, &mut face, &vec![0; (s.label.2 * s.label.3 * 4) as usize]);
    clip_to_silhouette(&s, &mut face);
    face
}

pub fn reference_interior(shape: usize, colour: [u8; 3]) -> CartFace {
    let s = spec(shape);
    let src = interior_asset(s.mould);
    let mask = if shape == 0 { None } else { Some(s.mask) };
    let mut rgba = src.to_vec();
    for (i, px) in rgba.chunks_exact_mut(4).enumerate() {
        if let Some(mask) = mask {
            px[3] = ((px[3] as u32 * mask[i] as u32 + 127) / 255) as u8;
        }
        if px[3] == 0 {
            continue;
        }
        for c in 0..3 {
            px[c] = overlay(px[c], colour[c]);
        }
        px[3] = ((u32::from(px[3]) * u32::from(CLEAR_ALPHA) + 127) / 255) as u8;
    }
    CartFace {
        rgba,
        w: s.w,
        h: s.h,
    }
}

pub fn reference_backing(shape: usize) -> CartFace {
    let s = spec(shape);
    if shape == 0 {
        return CartFace {
            rgba: board_asset(true).to_vec(),
            w: s.w,
            h: s.h,
        };
    }
    let mut rgba = board_asset(false).to_vec();
    for (px, cover) in rgba.chunks_exact_mut(4).zip(s.mask) {
        px[3] = ((px[3] as u32 * *cover as u32 + 127) / 255) as u8;
    }
    CartFace {
        rgba,
        w: s.w,
        h: s.h,
    }
}
