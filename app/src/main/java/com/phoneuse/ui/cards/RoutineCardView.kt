package com.phoneuse.ui.cards

import com.phoneuse.i18n.tr
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.cards.CardAction
import com.phoneuse.cards.RoutineCard
import com.phoneuse.routine.PlaceEdge
import com.phoneuse.routine.RoutineAction
import com.phoneuse.routine.RoutinePermissions
import com.phoneuse.routine.RoutineSystem
import com.phoneuse.routine.RoutineText
import com.phoneuse.routine.Trigger
import com.phoneuse.ui.routines.ActTag
import com.phoneuse.ui.routines.InfoRow
import com.phoneuse.ui.routines.RadiusMap
import com.phoneuse.ui.routines.Steps
import com.phoneuse.ui.routines.radiusText
import com.phoneuse.ui.theme.pu

/**
 * make_routine's preview (board RoutineBuilder in a card): when, the place on a map with its radius, the
 * conditions and the steps, then [고치기] / [루틴 저장]. Saving is the user's tap here, never the model. A permission
 * the routine still needs comes as the generic [권한 허용] card under it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutineCardView(card: RoutineCard, onAction: (CardAction) -> Unit, modifier: Modifier = Modifier) {
    val c = pu
    val context = LocalContext.current
    val routine = remember(card) { card.parsed() } ?: return
    val system = remember(context) { RoutineSystem.get(context) }
    val scope = rememberCoroutineScope()
    val all by system.store.routines.collectAsState()
    val saved = all.any { it.cardKey == card.key }
    // Read on every composition (cheap), so the card disappears once the permission is granted.
    val missing = system.missingPermission(routine)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardBox(padding = PaddingValues(0.dp), label = tr("Routine preview ${routine.title}", "루틴 미리보기 ${routine.title}")) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
                Text(tr("New routine", "새 루틴"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.textSub)
                Text(RoutineText.whenText(routine.trigger), Modifier.padding(top = 6.dp), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.textSub)
                Text(routine.title, Modifier.padding(top = 2.dp), fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            }
            val place = routine.trigger as? Trigger.Place
            if (place != null) {
                RadiusMap(place.lat, place.lng, place.radiusM, Modifier.fillMaxWidth().height(128.dp))
                Column(Modifier.padding(horizontal = 16.dp)) {
                    InfoRow(tr("Place", "장소"), listOfNotNull(place.name, card.placeAddress).joinToString(" · "), card.placeFrom, top = false)
                    InfoRow(tr("Arrive / leave", "도착 / 떠날 때"), if (place.on == PlaceEdge.ENTER) tr("On arrival", "도착하면") else tr("On leaving", "떠나면"))
                    InfoRow(tr("Radius", "범위"), radiusText(place.radiusM), mono = true)
                }
            }
            val chips = RoutineText.conditionChips(routine.conditions)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                if (chips.isNotEmpty()) {
                    FlowRow(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (chip in chips) ActTag(chip)
                    }
                }
                val ask = routine.action as? RoutineAction.Ask
                if (ask != null) {
                    // What actually runs on every trigger: the prompt itself; the model's steps only summarize it.
                    Text(tr("What to run each time", "매번 실행할 내용"), Modifier.padding(top = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.textSub)
                    Text(ask.prompt, Modifier.padding(top = 4.dp, bottom = 4.dp), fontSize = 14.sp, color = c.text)
                    if (ask.steps.isNotEmpty()) {
                        Text(tr("Summary", "요약"), Modifier.padding(top = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.textSub)
                        Steps(ask.steps)
                    }
                } else {
                    Steps(RoutineText.steps(routine.action))
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardButton(tr("Edit", "고치기"), { onAction(CardAction.Prompt(tr("I want to edit this routine", "이 루틴 고칠래"))) }, Modifier.weight(1f))
                CardButton(
                    if (saved) tr("Saved", "저장됨") else tr("Save routine", "루틴 저장"),
                    {
                        if (!saved) {
                            scope.launch {
                                if (system.edit { save(routine) }) {
                                    Toast.makeText(context, tr("Routine saved. You can switch it on or off under Routines in the menu", "루틴을 저장했어요. 메뉴의 루틴에서 켜고 끌 수 있어요"), Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, tr("Couldn't save", "저장하지 못했어요"), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    Modifier.weight(2f),
                    primary = !saved,
                )
            }
        }
        system.warning(routine)?.let { Text(it, Modifier.padding(horizontal = 4.dp), fontSize = 12.5.sp, color = c.textSub) }
        if (missing != null) {
            PermissionCardView(missing, onAction)
            if (missing.label == RoutinePermissions.ALWAYS_LABEL) {
                Text(RoutinePermissions.ALWAYS_HINT, Modifier.padding(horizontal = 4.dp), fontSize = 12.5.sp, color = c.textSub)
            }
        }
    }
}
