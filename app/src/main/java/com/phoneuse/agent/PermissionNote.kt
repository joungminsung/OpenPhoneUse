package com.phoneuse.agent

import com.phoneuse.i18n.tr

/** The tool-result note telling the model a permission card is in the chat. The button name follows the app language. */
object PermissionNote {
    fun text(head: String): String =
        "$head. A permission card is now in the chat: ask the user in one short sentence to tap [${tr("Allow permission", "권한 허용")}], then call finish."
}
