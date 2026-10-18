//! Straight-alpha source-over for the shelf's back plastic, board and front plastic.

pub(crate) fn over(back: &mut [u8], front: &[u8]) {
    assert_eq!(back.len(), front.len());
    for (dst, src) in back.chunks_exact_mut(4).zip(front.chunks_exact(4)) {
        let sa = u32::from(src[3]);
        let da = u32::from(dst[3]);
        let a = sa * 255 + da * (255 - sa);
        if a == 0 {
            dst.fill(0);
            continue;
        }
        for c in 0..3 {
            let colour = u32::from(src[c]) * sa * 255
                + u32::from(dst[c]) * da * (255 - sa);
            dst[c] = ((colour + a / 2) / a) as u8;
        }
        dst[3] = ((a + 127) / 255) as u8;
    }
}
