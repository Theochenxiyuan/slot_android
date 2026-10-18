//! Reproduce Slot's SVG rasterization, edge-bled runtime textures and Android test references.
use std::{
    fs,
    io::{BufWriter, Write},
    path::{Path, PathBuf},
};
mod material;
mod silhouette;

// Same logical pixel sizes and label panels as slot-ui/src/cart.rs.
mod cart {
    pub const SEATED_W: u32 = 240;
    pub const CART_W: u32 = 360;
    pub const CART_H: u32 = (CART_W * 141 + SEATED_W / 2) / SEATED_W;
    pub const GB_CART_W: u32 = 312;
    pub const GB_CART_H: u32 = (GB_CART_W * 259 + SEATED_W / 2) / SEATED_W;
    pub const GBC_LABEL_X: u32 = 45;
    pub const GBC_LABEL_Y: u32 = 110;
    pub const GBC_LABEL_W: u32 = 223;
    pub const GBC_LABEL_H: u32 = 199;
    pub struct CartFace {
        pub rgba: Vec<u8>,
        pub w: u32,
        pub h: u32,
    }
    pub const fn label_panel(w: u32, h: u32) -> (u32, u32, u32, u32) {
        (
            (w * 133 + 500) / 1000,
            (h * 236 + 500) / 1000,
            (w * 867 + 500) / 1000,
            (h * 877 + 500) / 1000,
        )
    }
    pub const fn gb_label_panel(w: u32, h: u32) -> (u32, u32, u32, u32) {
        (
            (w * 147 + 500) / 1000,
            (h * 291 + 500) / 1000,
            (w * 854 + 500) / 1000,
            (h * 868 + 500) / 1000,
        )
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Finish {
    Solid,
    Translucent,
    Glitter,
}

pub struct Shell {
    pub colour: [u8; 3],
    pub finish: Finish,
}

fn write_png(path: &Path, rgba: &[u8], w: u32, h: u32) -> Result<(), Box<dyn std::error::Error>> {
    let file = fs::File::create(path)?;
    let mut enc = png::Encoder::new(BufWriter::new(file), w, h);
    enc.set_color(png::ColorType::Rgba);
    enc.set_depth(png::BitDepth::Eight);
    let mut writer = enc.write_header()?;
    writer.write_image_data(rgba)?;
    Ok(())
}

fn write_argb(path: &Path, face: &cart::CartFace) -> Result<(), Box<dyn std::error::Error>> {
    let mut file = BufWriter::new(fs::File::create(path)?);
    file.write_all(&face.w.to_be_bytes())?;
    file.write_all(&face.h.to_be_bytes())?;
    for p in face.rgba.chunks_exact(4) {
        file.write_all(&[p[3], p[0], p[1], p[2]])?;
    }
    Ok(())
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let root = manifest.join("../..");
    let runtime = root.join("app/src/main/assets/cartridges");
    let refs = root.join("app/src/androidTest/assets/cartridges");
    fs::create_dir_all(&runtime)?;
    fs::create_dir_all(&refs)?;

    for (name, w, h, mask) in [
        ("gba", cart::CART_W, cart::CART_H, silhouette::cart_mask()),
        (
            "gb",
            cart::GB_CART_W,
            cart::GB_CART_H,
            silhouette::gb_cart_mask(silhouette::GbShell::Notched),
        ),
        (
            "gbc",
            cart::GB_CART_W,
            cart::GB_CART_H,
            silhouette::gb_cart_mask(silhouette::GbShell::Rounded),
        ),
    ] {
        let mut file = fs::File::create(runtime.join(format!("{name}.mask")))?;
        file.write_all(&w.to_be_bytes())?;
        file.write_all(&h.to_be_bytes())?;
        file.write_all(mask)?;
        println!("{name}.mask: {w}x{h}");
    }

    for (name, bytes, w, h) in material::runtime_png_sources() {
        let rgba = material::decode_and_bleed(bytes, w, h);
        write_png(&runtime.join(name), &rgba, w, h)?;
        println!("{name}: {w}x{h}");
    }

    for (name, shape, colour, finish) in [
        ("gba-solid", 0, [0xc4, 0xab, 0x59], Finish::Solid),
        ("gba-clear", 0, [0x57, 0x5c, 0x87], Finish::Translucent),
        ("gba-glitter", 0, [0x86, 0xb9, 0xbf], Finish::Glitter),
        ("gb-solid", 1, [0x9a, 0x97, 0x8f], Finish::Solid),
        ("gb-clear", 1, [0x7c, 0x7a, 0x8a], Finish::Translucent),
        ("gbc-clear", 2, [0x7c, 0x7a, 0x8a], Finish::Translucent),
        ("gbc-glitter", 2, [0x86, 0xb9, 0xbf], Finish::Glitter),
    ] {
        let face = material::reference(shape, colour, finish);
        write_argb(&refs.join(format!("{name}.argb")), &face)?;
    }

    for (name, shape, colour) in [
        ("gba-interior", 0, [0x57, 0x5c, 0x87]),
        ("gb-interior", 1, [0x7c, 0x7a, 0x8a]),
        ("gbc-interior", 2, [0x7c, 0x7a, 0x8a]),
    ] {
        let face = material::reference_interior(shape, colour);
        write_argb(&refs.join(format!("{name}.argb")), &face)?;
    }

    for (name, shape) in [("gba-backing", 0), ("gb-backing", 1), ("gbc-backing", 2)] {
        let face = material::reference_backing(shape);
        write_argb(&refs.join(format!("{name}.argb")), &face)?;
    }

    Ok(())
}
