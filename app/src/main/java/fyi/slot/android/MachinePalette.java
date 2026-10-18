package fyi.slot.android;

/** Dark and light plastic colours shared by the slot, housing and battery bay. */
final class MachinePalette {
    /** Cavity floor below the ledge stays dark in both themes. */
    static final int[] FLOOR = {0xff313131, 0xff212121, 0xff1f1f1f, 0xff121212};
    static final float[] FLOOR_STOPS = {0f, .2f, .85f, 1f};
    static final float[] WALL_STOPS = {0f, .08f, .2f, .78f, 1f};

    static final MachinePalette DARK = new MachinePalette(
            new int[]{0xff2c2c31, 0xff26262b, 0xff202025}, .07f, new int[]{0x4dffffff, 0x00ffffff},
            0x59ffffff, 0x8c000000,
            new int[]{0xff58585a, 0xff3c3c3e, 0xff1a1a1c, 0xff161618, 0xff0a0a0b},
            0xff19191d, .025f, 0xd9000000, 0x1fffffff, 0x26ffffff,
            0xff606069, 0xff08080b, 0xff16161b, 0xffe0e0e1, 0xff17171c, 0x1fffffff);

    static final MachinePalette LIGHT = new MachinePalette(
            new int[]{0xffe6e6e9, 0xffdcdce0, 0xffd2d2d7}, .1f, new int[]{0x80ffffff, 0x00ffffff},
            0xb3ffffff, 0x8c000000,
            new int[]{0xffcdcdcf, 0xffbcbcbe, 0xffb0b0b3, 0xffa8a8ab, 0xff5c5c5f},
            0xffcacad0, .04f, 0x80303040, 0x8cffffff, 0xb3ffffff,
            0xff787882, 0xffa9a9b2, 0xffbfbfc7, 0xff1e1e24, 0xfff4f4f6, 0x18000000);

    final int[] housing, topHighlight, wall;
    final float grain, bayGrain;
    final int rimLight, innerShadow;
    final int bay, bayShadow, bayLowerLight, bayRim;
    final int title, titlePlateTop, titlePlateBottom, ink, badgeInk, pressed;

    private MachinePalette(int[] housing, float grain, int[] topHighlight, int rimLight, int innerShadow, int[] wall,
                           int bay, float bayGrain, int bayShadow, int bayLowerLight, int bayRim,
                           int title, int titlePlateTop, int titlePlateBottom, int ink, int badgeInk, int pressed) {
        this.housing = housing; this.grain = grain; this.topHighlight = topHighlight;
        this.rimLight = rimLight; this.innerShadow = innerShadow; this.wall = wall;
        this.bay = bay; this.bayGrain = bayGrain; this.bayShadow = bayShadow;
        this.bayLowerLight = bayLowerLight; this.bayRim = bayRim;
        this.title = title; this.titlePlateTop = titlePlateTop; this.titlePlateBottom = titlePlateBottom;
        this.ink = ink; this.badgeInk = badgeInk; this.pressed = pressed;
    }
}
