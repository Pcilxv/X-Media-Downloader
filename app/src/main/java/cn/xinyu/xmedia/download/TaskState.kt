package cn.xinyu.xmedia.download

import kotlinx.coroutines.flow.MutableStateFlow

data class TaskState(val running: Boolean = false, val message: String = "", val percent: Int? = null, val error: Boolean = false)
object DownloadState { val state = MutableStateFlow(TaskState()) }
