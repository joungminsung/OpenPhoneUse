package com.phoneuse.chat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The one open helper for `chat.db`, shared by conversations, memories, app tips and search, so a single
 * connection pool serves the file and every table upgrades together ([ChatDbSchema]).
 */
class ChatDb private constructor(context: Context) : SQLiteOpenHelper(context, NAME, null, ChatDbSchema.VERSION) {
    val readable: SQLiteDatabase get() = readableDatabase
    val writable: SQLiteDatabase get() = writableDatabase

    override fun onCreate(db: SQLiteDatabase) {
        ChatDbSchema.create.forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        ChatDbSchema.migrations(oldVersion, newVersion).forEach(db::execSQL)
    }

    companion object {
        const val NAME = "chat.db"

        @Volatile private var instance: ChatDb? = null

        fun get(context: Context): ChatDb =
            instance ?: synchronized(this) { instance ?: ChatDb(context.applicationContext).also { instance = it } }
    }
}
