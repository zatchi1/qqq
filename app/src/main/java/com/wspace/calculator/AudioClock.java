package com.wspace.calculator;

/** Tells an animation how far a sound has really played, so picture and audio stay in step. */
interface AudioClock {
    /** Seconds played so far, or a negative number when the sound is not playing. */
    float playheadSec();
}
