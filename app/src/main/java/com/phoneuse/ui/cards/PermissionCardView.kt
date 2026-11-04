package com.phoneuse.ui.cards

import com.phoneuse.i18n.tr
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.cards.CardAction
import com.phoneuse.cards.CardActions
import com.phoneuse.cards.PermissionCard
import com.phoneuse.ui.theme.pu

/** "위치 권한이 필요해요" with [권한 허용]; the tool that needed it said "권한 필요: <label>". */
@Composable
fun PermissionCardView(card: PermissionCard, onAction: (CardAction) -> Unit, modifier: Modifier = Modifier) {
    val c = pu
    CardBox(modifier, label = tr("${card.label} permission request", "${card.label} 권한 요청")) {
        Text(tr("${card.label} permission needed", "${card.label} 권한이 필요해요"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        Text(
            if (card.settings == PermissionCard.SETTINGS_NOTIFICATION_LISTENER) {
                tr("Turn on OpenPhoneUse in Settings. When you come back, I'll pick up where I left off.", "설정 화면에서 OpenPhoneUse를 켜 주세요. 돌아오면 하던 일을 이어서 할게요.")
            } else {
                tr("Once you allow it, I'll pick up where I left off. I only use it when needed.", "허용하면 하던 일을 이어서 할게요. 필요할 때만 써요.")
            },
            fontSize = 13.sp,
            color = c.textSub,
            modifier = Modifier.padding(top = 2.dp),
        )
        CardButton(
            tr("Allow permission", "권한 허용"),
            { onAction(CardActions.requestPermission(card)) },
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            primary = true,
        )
    }
}
