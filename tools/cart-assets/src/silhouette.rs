// From BrandonKowalski/slot 34ac38031fd903eb2a0a0157f1dea4d3a5546195.
// GPL-3.0-or-later; see THIRD_PARTY.md.
use std::sync::OnceLock;

use crate::cart::{CART_H, CART_W, GB_CART_H, GB_CART_W};

const CART_SVG: &str = include_str!("../assets/cart.svg");
const GB_CART_SVG: &str = include_str!("../assets/gb_cart.svg");
const GBC_CART_SVG: &str = include_str!("../assets/gbc_cart.svg");

#[derive(Copy, Clone, PartialEq, Eq, Debug)]
pub enum GbShell {
    Notched,
    Rounded,
}

pub fn silhouette(w: u32, h: u32) -> Vec<u8> {
    rasterise_svg(CART_SVG, w, h).unwrap_or_else(|| vec![255; (w * h) as usize])
}

pub fn gb_silhouette(shell: GbShell, w: u32, h: u32) -> Vec<u8> {
    let notched = rasterise_svg(GB_CART_SVG, w, h).unwrap_or_else(|| vec![255; (w * h) as usize]);
    match shell {
        GbShell::Notched => notched,
        GbShell::Rounded => {
            let mut rounded =
                rasterise_svg(GBC_CART_SVG, w, h).unwrap_or_else(|| vec![255; (w * h) as usize]);
            // The two moulds share one body below the shoulder (see examples/raster_templates.rs).
            let shoulder = (30 * h / GB_CART_H).min(h);
            for y in shoulder..h {
                for x in 0..w {
                    let i = (y * w + x) as usize;
                    rounded[i] = notched[i];
                }
            }
            rounded
        }
    }
}

pub(crate) fn cart_mask() -> &'static [u8] {
    static MASK: OnceLock<Vec<u8>> = OnceLock::new();
    MASK.get_or_init(|| silhouette(CART_W, CART_H))
}

pub(crate) fn gb_cart_mask(shell: GbShell) -> &'static [u8] {
    static NOTCHED: OnceLock<Vec<u8>> = OnceLock::new();
    static ROUNDED: OnceLock<Vec<u8>> = OnceLock::new();
    let lock = match shell {
        GbShell::Notched => &NOTCHED,
        GbShell::Rounded => &ROUNDED,
    };
    lock.get_or_init(|| gb_silhouette(shell, GB_CART_W, GB_CART_H))
}

fn rasterise_svg(svg: &str, w: u32, h: u32) -> Option<Vec<u8>> {
    let tree = usvg::Tree::from_str(svg, &usvg::Options::default()).ok()?;
    let mut pixmap = resvg::tiny_skia::Pixmap::new(w, h)?;
    let size = tree.size();
    let scale =
        resvg::tiny_skia::Transform::from_scale(w as f32 / size.width(), h as f32 / size.height());
    resvg::render(&tree, scale, &mut pixmap.as_mut());
    Some(pixmap.data().iter().skip(3).step_by(4).copied().collect())
}
