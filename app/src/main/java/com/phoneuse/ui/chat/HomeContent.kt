package com.phoneuse.ui.chat

import com.phoneuse.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.chat.Conversation
import com.phoneuse.proactive.NudgeAction
import com.phoneuse.proactive.NudgeLog
import com.phoneuse.proactive.SentNudge
import com.phoneuse.today.TodayTile
import com.phoneuse.ui.home.NudgeCards
import com.phoneuse.ui.theme.MonoStyle
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.pu
import kotlinx.coroutines.delay
import java.time.ZoneId

/** A home quick action: tapping it puts [prompt] into the input. */
data class QuickAction(
    val label: String,
    val prompt: String,
    val icon: () -> ImageVector,
    val accent: Boolean = false,
    /** Board UnfoldedHome's one-row label. */
    val short: String = label,
)

val QUICK_ACTIONS get() = listOf(
    QuickAction(tr("Find nearby", "근처 찾기"), tr("Find a pharmacy that's open nearby", "근처에 문 연 약국 찾아줘"), { PuIcons.Pin }, accent = true, short = tr("Nearby", "근처")),
    QuickAction(tr("When to go?", "언제 나가?"), tr("I'm meeting at Gangnam Station at 7. When should I leave?", "7시에 강남역 약속인데 언제 나가야 돼?"), { PuIcons.Route }, short = tr("Directions", "길찾기")),
    QuickAction(tr("Find photos", "사진 찾기"), tr("Sort today's photos into folders by date", "오늘 찍은 사진 날짜별 폴더로 정리해줘"), { PuIcons.Photo }, short = tr("Photos", "사진")),
    QuickAction(tr("Sum up alerts", "알림 요약"), tr("Summarize my unread notifications", "안 읽은 알림 요약해줘"), { PuIcons.Lines }, short = tr("Alerts", "알림")),
    QuickAction(tr("Add event", "일정 넣기"), tr("Add a dentist appointment tomorrow at 3 PM", "내일 오후 3시 치과 일정 넣어줘"), { PuIcons.Calendar }, short = tr("Events", "일정")),
    QuickAction(tr("Tidy files", "파일 정리"), tr("Tidy up my Downloads folder", "다운로드 폴더 정리해줘"), { PuIcons.Folder }, short = tr("Files", "파일")),
)

/** From this width the home takes board UnfoldedHome's layout (the unfolded two-pane chat column). */
private val WIDE_HOME = 520.dp

/**
 * Board Main (and UnfoldedHome when wide): date line, title, the nudge cards, the "오늘 챙길 것" tiles, 바로 해보기, and
 * the latest conversation. Without nudges and tiles it is the plain home.
 */
@Composable
fun HomeContent(
    latest: Conversation?,
    onQuickAction: (QuickAction) -> Unit,
    onContinue: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** TodaySummary's tiles; empty draws none. */
    tiles: List<TodayTile> = emptyList(),
    tileActions: TodayTileActions = TodayTileActions(),
    /** The nudge log ("알아서 챙기기"); the pending, unexpired ones show as cards above the tiles. */
    nudgeLog: List<SentNudge> = emptyList(),
    onNudgeAction: (SentNudge, NudgeAction) -> Unit = { _, _ -> },
    onNudgeClose: (SentNudge) -> Unit = {},
) {
    val c = pu
    val zone = remember { ZoneId.systemDefault() }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000L - value % 60_000L)
        }
    }
    BoxWithConstraints(modifier) {
        val wide = maxWidth >= WIDE_HOME
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (wide) 18.dp else 22.dp),
        ) {
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(homeDateLine(now, zone), style = MonoStyle, fontSize = 12.5.sp, color = c.textSub)
                Text(tr("Today", "오늘 챙길 것"), fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, color = c.text)
            }

            val nudges = remember(nudgeLog, now) { NudgeLog.active(nudgeLog, now) }
            if (nudges.isNotEmpty()) NudgeCards(nudges, now, onNudgeAction, onNudgeClose)

            if (tiles.isNotEmpty()) {
                if (wide) TodayTilesWide(tiles, tileActions) else TodayTiles(tiles, tileActions)
            }

            if (wide) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (qa in QUICK_ACTIONS) QuickTile(qa, Modifier.weight(1f).fillMaxHeight(), label = qa.short) { onQuickAction(qa) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr("Try it now", "바로 해보기"), Modifier.padding(horizontal = 4.dp), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.textSub)
                    for (row in QUICK_ACTIONS.chunked(3)) {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (qa in row) QuickTile(qa, Modifier.weight(1f).fillMaxHeight()) { onQuickAction(qa) }
                        }
                    }
                }
            }

            if (latest != null) {
                Column {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clickable(role = Role.Button) { onContinue(latest.id) }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            buildAnnotatedString {
                                withStyle(androidx.compose.ui.text.SpanStyle(color = c.textSub)) { append(tr("Continue · ", "이어서 · ")) }
                                append(latest.title.ifBlank { tr("New chat", "새 대화") })
                            },
                            Modifier.weight(1f),
                            fontSize = 14.sp,
                            color = c.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(relativeDay(latest.updatedAt, now, zone), style = MonoStyle, fontSize = 12.5.sp, color = c.textSub)
                    }
                }
            }
        }
    }
}

/** 78dp tall while its label fits on one line; a label that does not fit wraps and the tile grows, never clips. */
@Composable
private fun QuickTile(qa: QuickAction, modifier: Modifier, label: String = qa.label, onClick: () -> Unit) {
    val c = pu
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .heightIn(min = 78.dp)
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape)
            .clickable(role = Role.Button, onClickLabel = tr("Put an example in the input", "입력창에 예시 넣기"), onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(qa.icon(), null, tint = if (qa.accent) c.accent else c.text, modifier = Modifier.size(22.dp))
        Text(label, Modifier.padding(top = 4.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.text)
    }
}
