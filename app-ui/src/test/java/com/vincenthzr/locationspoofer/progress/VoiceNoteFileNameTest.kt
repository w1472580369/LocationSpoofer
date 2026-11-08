package com.vincenthzr.locationspoofer.progress

import com.vincenthzr.locationspoofer.ui.audio.VoiceRecorder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** 回归测试：录音文件按录音时间（先后顺序）用数字顺序命名，如 voice_note_1、voice_note_2 ... */
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

    /** 模拟每次录制收到一个编号：编号必须严格 1、2、3 … 递增，不能重复或回退 */
    @Test
    fun `consecutive recordings are numbered 1 2 3 in order without repeating`() {
        val existing = mutableListOf<File>()
        repeat(10) { number ->
            // 下一条录音的编号必须等于已有最大编号 + 1（即第 number + 1 号）
            assertEquals((number + 1).toLong(), VoiceRecorder.nextRecordingIndex(existing).toLong())
            existing += file("voice_note_${number + 1}.m4a")
        }
    }

    /** 两个连续的编号不能指向同一个文件（并发抢占时的退路：编号已存在就跳过一个编号） */
    @Test
    fun `two consecutive recordings must not report the same first number`() {
        val first = VoiceRecorder.nextRecordingIndex(emptyList())
        val second = VoiceRecorder.nextRecordingIndex(listOf(file("voice_note_1.m4a")))
        assert(first != second) { "recording numbers must not repeat" }
        assertEquals(1, first)
        assertEquals(2, second)
    }

    /** 3gp 时代的旧文件会被 4 以上的 m4a 编号追赶，但扩展名匹配时必须算数 */
    @Test
    fun `only matching extensions count toward the next number`() {
        assertEquals(
            3,
            VoiceRecorder.nextRecordingIndex(
                listOf(
                    file("voice_note_1.3gp"),
                    file("voice_note_2.3gp"),
                    file("voice_note_3.mp3")
                ),
                "3gp"
            )
        )
        assertEquals(
            1,
            VoiceRecorder.nextRecordingIndex(
                listOf(
                    file("voice_note_1.m4a"),
                    file("voice_note_2.txt")
                ),
                "3gp"
            )
        )
    }

    /**
     * 根因回归：`File.extension` 不含开头的点，如果扩展名比较时带了点，已经录过的文件就
     * 永远算不进已编号文件，导致下一条录音永远指向 `voice_note_1.m4a`（编号不递增、重复落盘、
     * 列表 key 冲突）。这里确认：对比用的扩展名不能带点。
     */
    @Test
    fun `extension matching must not include the leading dot`() {
        // 不建真实文件也能确认 File.extension 的字符串规则
        val real = listOf("voice_note_1.m4a", "voice_note_2.m4a", "voice_note_3.m4a").map { File("voice_notes", it) }
        // File.extension 不含点，所以带点的比较永远对不上（旧版 bug 的根因）
        assertEquals(0, real.filter { it.extension == ".m4a" }.size)
        assertEquals(3, real.filter { it.extension == "m4a" }.size)
        assertEquals(1, VoiceRecorder.nextRecordingIndex(real, ".m4a"))
        assertEquals(4, VoiceRecorder.nextRecordingIndex(real, "m4a"))
    }
}
