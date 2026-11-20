package ly.com.tahaben.farhan.settings

internal enum class BackupAction(val intentValue: String) {
    Export("export"),
    Import("import");

    companion object {
        fun fromIntentValue(value: String?): BackupAction? = entries.firstOrNull { it.intentValue == value }
    }
}
