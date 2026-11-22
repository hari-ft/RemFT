package com.ftapps.remft.domain.tasks

enum class TaskStatus(val db: String) {
    Inbox("inbox"),
    Open("open"),
    Scheduled("scheduled"),
    InProgress("in_progress"),
    Waiting("waiting"),
    Completed("completed"),
    Cancelled("cancelled"),
    Archived("archived");

    val isPlanning: Boolean
        get() = this == Open || this == Scheduled || this == InProgress || this == Waiting

    val isTerminal: Boolean
        get() = this == Completed || this == Cancelled || this == Archived

    companion object {
        fun parse(value: String?): TaskStatus? = entries.firstOrNull { it.db == value }

        val planning: List<TaskStatus> get() = entries.filter { it.isPlanning }

        val terminal: List<TaskStatus> get() = entries.filter { it.isTerminal }

        val planningDb: List<String> get() = planning.map { it.db }

        fun isTerminalDb(value: String?): Boolean = parse(value)?.isTerminal == true
    }
}

enum class TaskPriority(val db: String) {
    None("none"),
    Low("low"),
    Medium("medium"),
    High("high"),
    Urgent("urgent");

    val rank: Int get() = ordinal

    companion object {
        fun parse(value: String?): TaskPriority = entries.firstOrNull { it.db == value } ?: None

        fun rankOf(value: String?): Int = entries.firstOrNull { it.db == value }?.ordinal ?: 0
    }
}
