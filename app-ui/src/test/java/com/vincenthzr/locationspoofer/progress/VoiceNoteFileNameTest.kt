package com.vincenthzr.locationspoofer.progress

import com.vincenthzr.locationspoofer.ui.audio.VoiceRecorder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** 回归测试：录音文件按录音时间（先后顺序）用数字顺序命名，如 voice_note_1、voice_note_2 ... */
class VoiceNoteFileNameTest {

    private fun file(name: String) = File("voice_notes", name)

    @Test
    fun `no existing files start from 1`() {
        assertEquals(1, VoiceRecorder.nextRecordingIndex(emptyList()))
    }

    @Test
    fun `files without a note number start from 1`() {
        assertEquals(1, VoiceRecorder.nextRecordingIndex(listOf(file("other.m4a"))))
    }

    @Test
    fun `the next note index follows the largest existing note number`() {
        assertEquals(
            4,
            VoiceRecorder.nextRecordingIndex(listOf(file("voice_note_1.m4a"), file("voice_note_2.m4a"), file("voice_note_3.m4a")))
        )
    }

    @Test
    fun `non audio files and old prefixes are ignored`() {
        assertEquals(
            2,
            VoiceRecorder.nextRecordingIndex(listOf(file("voice_note_1.m4a"), file("voice_note_2.txt"), file("record_999.mp3")))
        )
    }

    @Test
    fun `a gap between existing numbers does not cause a duplicate`() {
        assertEquals(
            13,
            VoiceRecorder.nextRecordingIndex(listOf(file("voice_note_1.m4a"), file("voice_note_10.m4a"), file("voice_note_12.m4a")))
        )
    }
}
