package io.github.drivevillage.data

import android.system.Os
import android.system.OsConstants
import java.io.File

/** rename 결과(디렉터리 항목)를 디스크에 남기기 위한 부모 디렉터리 fsync. 실패하면 예외. */
object AndroidDirSync {
    fun sync(dir: File) {
        val fd = Os.open(dir.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
}
