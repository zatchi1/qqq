package com.wspace.calculator;

/** Pixel-art data for the little green one-eyed character (pure Java, unit-tested). */
final class SpriteData {
    private SpriteData() {}

    static final int W = 22;
    static final int H = 26;

    /** '.' = transparent. One char per pixel. */
    static final String[] ROWS = {
        ".............kk.......",
        "............khhk......",
        "..........kkkhhhk.....",
        "......kkkkggkhhhhk....",
        ".....kgggggggghhhhk...",
        "....kggggggggghhhhhk..",
        "..kkggggggggggghhhhhk.",
        ".khhgggkkggggggghhhhhk",
        ".khhhkkcckgggggghhhhhk",
        "khhkkcccckkkkgggggggk.",
        ".kkcccccccccckkgggGGk.",
        ".kkccccwwwwwwcckggGGk.",
        ".kgkkcwwwwwwwwcckgGGk.",
        ".kggkcwwwwweweccCkGGk.",
        ".kgkcccwwweeeeeCCkGGk.",
        ".kggkccccwweeeCCCkGGk.",
        ".kggkccccccceCCCkgGGk.",
        ".kbbbkcccccCCCCkggGGk.",
        ".kgkbbkkcCCkCkkgggGGk.",
        ".kggkkkkkkkkkgggggGGk.",
        ".kgggktttkggggggggGGk.",
        ".kgggktTtkggggggggGGk.",
        ".kgggktTtkggggggggGGk.",
        ".kGgGGkttkGGGGGGGGGGk.",
        "..kGGGGkkGGGGGGGGGGk..",
        "...kkkkkkkkkkkkkkkk..."
    };

    static int color(char ch) {
        switch (ch) {
            case 'k': return 0xFF14141E; // outline
            case 'g': return 0xFF92C673; // body
            case 'G': return 0xFF6FA855; // body shade
            case 'h': return 0xFF454A68; // horn
            case 'c': return 0xFF5AC7A8; // visor
            case 'C': return 0xFF3FA58C; // visor shade
            case 'w': return 0xFFBDFCF3; // visor highlight
            case 'e': return 0xFFF4FFFC; // heart
            case 'b': return 0xFF6FA24A; // blush
            case 't': return 0xFF3E8E62; // tongue
            case 'T': return 0xFF287048; // tongue shade
            default:  return 0;
        }
    }
}
