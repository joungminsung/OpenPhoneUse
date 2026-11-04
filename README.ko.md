<p align="center"><a href="README.md">English</a> | <b>한국어</b> | <a href="README.ja.md">日本語</a> | <a href="README.zh-CN.md">简体中文</a></p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/media/brand/icon-512.png">
    <source media="(prefers-color-scheme: light)" srcset="docs/media/brand/icon-light-512.png">
    <img src="docs/media/brand/icon-light-512.png" alt="OpenPhoneUse 아이콘: 파랑에서 주황으로 이어지는 폰 윤곽선과 오른쪽 아래의 점" width="128" height="128">
  </picture>
</p>

<div align="center">

# OpenPhoneUse

**OpenPhoneUse는 AI 에이전트가 사람처럼 휴대폰을 조작하게 해 주는 오픈소스 안드로이드 앱입니다. 화면을 읽고, 누르고, 입력하고, 스크롤하고, 앱 사이를 오가며, 이 모든 일을 PC·루트·ADB·Shizuku 없이 폰 안에서만 처리합니다.**

<a href="https://github.com/joungminsung/openphoneuse/releases/latest/download/OpenPhoneUse.apk"><img src="https://img.shields.io/badge/APK_%EB%82%B4%EB%A0%A4%EB%B0%9B%EA%B8%B0-Android_11%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="APK 내려받기" height="44"></a>

![Platform](https://img.shields.io/badge/platform-Android%2011%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)
![Tests](https://img.shields.io/badge/unit%20tests-1225%20passing-brightgreen)
[![Benchmark](https://img.shields.io/badge/benchmark-94.5%2F100-orange)](#벤치마크)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

<a href="docs/media/film.mp4"><img src="docs/media/film-hero.gif" alt="46초 OpenPhoneUse 영상의 일부 (영어)" width="800"></a>

<sub><a href="docs/media/film.mp4"><b>46초 영상 보기</b></a> (MP4, 소리 없음, 영어). 실제 앱 스크린샷으로 만든 편집 영상입니다.</sub>

[데모](#데모) · [할 수 있는 일](#할-수-있는-일) · [설치](#설치) · [동작 원리](#동작-원리) · [안전](#안전과-개인정보) · [벤치마크](#벤치마크) · [비교](#다른-방식과의-비교) · [FAQ](#자주-묻는-질문) · [기여하기](#기여하기)

</div>

---

## 내 폰을 대신 써 주는 안드로이드 AI 에이전트

평소 말하듯 시키면 됩니다. 예를 들어 "잘 준비해 줘: 다크 모드 켜고, 아침 6시 30분 알람 맞추고, 방해 금지 모드 켜 줘"라고 하면, OpenPhoneUse가 화면을 읽고 무엇을 할지 정한 뒤 사람처럼 앱을 다룹니다. 컴퓨터를 대신 다루는 "computer use" 에이전트의 휴대폰 버전, 즉 **폰 유즈(phone use) 에이전트**이자 **모바일 GUI 에이전트**이고, 앱 쪽에서 연동을 따로 지원하지 않아도 됩니다.

- **폰 하나로 동작합니다.** PC, 루트, ADB, Shizuku가 필요 없습니다. 접근성 서비스 하나를 켜고 모델에 로그인하면 끝입니다.
- **이미 깔려 있는 앱을 그대로 씁니다.** 접근성 트리와 스크린샷을 함께 읽기 때문에 게임, 지도, 차트처럼 그려진 화면도 다룹니다.
- **모델은 직접 고릅니다.** ChatGPT 요금제, OpenAI API 키, Claude, Grok, Gemini 중 원하는 것을 쓰면 됩니다.
- **먼저 묻습니다.** 결제와 보안 설정 변경은 항상 묻습니다. 삭제는 직접 요청한 되돌릴 수 있는 항목 하나가 아니면 묻고, 전송은 받는 사람과 내용을 직접 말한 경우가 아니면 묻습니다. 비밀번호는 입력하지 않고 사용자에게 넘깁니다.

**벤치마크 94.5 / 100점.** 9개 분야 31개 과제에서 받은 점수이고, 과제당 소요 시간 중앙값은 42초입니다(GPT-6.1 Terra, 빠른 모드, 안드로이드 에뮬레이터, 2026-11-03). [채점 방식 보기](#벤치마크).

**[APK 내려받기](https://github.com/joungminsung/openphoneuse/releases/latest/download/OpenPhoneUse.apk)** 또는 [설치 방법](#설치)을 보세요.

## 데모

<p align="center">
  <img src="docs/media/ko/demo-ui-tour.gif" alt="UI 둘러보기: 홈 화면, 모델 선택, 단계가 표시된 완료된 작업, 결제 확인 카드, 다른 앱 위에서 동작 중인 오버레이" width="320">
</p>

<p align="center"><sub>안드로이드 에뮬레이터에서 실행한 실제 앱입니다. 샘플 대화는 미리 넣어 둔 것이고, 오버레이 상태는 디버그 브로드캐스트로 띄웠습니다(<a href="CONTRIBUTING.md#screenshots-and-the-demo-gif">만든 방법</a>, 영어). <a href="docs/media/ko/demo-ui-tour.mp4">MP4로 보기</a>.</sub></p>

<table>
<tr>
<td align="center" width="25%"><img src="docs/media/ko/screen-chat.png" alt="에이전트가 거친 단계, 비교 표, 강조된 선택 이유가 담긴 답변"><br><b>과정이 보이는 답변</b></td>
<td align="center" width="25%"><img src="docs/media/ko/screen-confirm.png" alt="결제 전에 확인을 요청하는 카드"><br><b>위험한 동작은 먼저 확인</b></td>
<td align="center" width="25%"><img src="docs/media/ko/screen-running.png" alt="설정 앱을 조작하는 동안 표시되는 상태 오버레이와 중지 버튼"><br><b>다른 앱 안에서 동작</b></td>
<td align="center" width="25%"><img src="docs/media/ko/screen-routines.png" alt="'회사 도착하면 무음' 같은 템플릿이 있는 루틴 화면"><br><b>루틴과 알림 제안</b></td>
</tr>
<tr>
<td align="center" width="25%"><img src="docs/media/ko/screen-model.png" alt="모델 목록, 빠른 모드 스위치, 추론 강도가 있는 모델 선택 화면"><br><b>모델과 속도 선택</b></td>
<td align="center" width="25%"><img src="docs/media/ko/screen-settings.png" alt="ChatGPT 요금제나 API 키로 로그인하는 설정 화면"><br><b>요금제 또는 API 키</b></td>
<td align="center" colspan="2" width="50%"><img src="docs/media/ko/screen-fold.png" alt="펼친 폴더블의 2분할 화면: 대화 목록과 채팅이 나란히 보임"><br><b>폴더블 2분할 화면</b></td>
</tr>
</table>

## 할 수 있는 일

아래 요청은 [벤치마크 과제](bench/suites/v2.json)에서 가져와 줄여 쓴 것이고, 원문은 한국어 또는 영어입니다. 과제마다 새 대화에서 그대로 입력합니다.

| 분야 | 예시 요청 |
|---|---|
| **설정** | "잘 준비해 줘: 다크 모드, 방해 금지, 밝기 최저, 아침 6시 40분 알람" · "유튜브 알림 전부 꺼 줘" |
| **일상** | "아빠가 쓰실 폰이야. 글자와 화면 크기를 최대한 크게 하고 굵은 글씨도 켜 줘." · "배터리가 오래가게 해 주고, 어떤 앱이 제일 많이 썼는지 알려 줘" |
| **앱** | "평일에만 울리는 알람 3개를 6시, 6시 10분, 6시 20분에 '기상'이라는 이름으로 맞춰 줘" · "사진 한 장 찍고, 그 사진을 열어서 보여 줘" |
| **연쇄 작업** | "오늘 서울 일몰 시각을 찾아서 30분 전에 알람 맞춰 줘" · "내일 비 올 확률이 30%를 넘으면 7시에 '우산 챙기기' 알람, 아니면 7시 30분에 맞춰 줘" |
| **지도** | "구글 지도에서 서울역에서 강남역까지 가장 빠른 대중교통 경로를 대안과 출발 시각까지 알려 줘" · "타임스스퀘어 근처에서 평점 4.5 이상인 카페 3곳" |
| **리서치** | "아마존, 베스트바이, 월마트의 에어팟 프로 3 현재 가격 비교해 줘" · "다음 주에 도쿄 3일 여행 가. 날씨 확인하고 하루씩 일정 짜 줘." |
| **크롬으로 웹 읽기** | "영어 위키백과의 가장 높은 건물 목록을 열어서 상위 5개를 알려 줘" · "이 피자 주문 양식 채워 줘" |
| **미디어** | "유튜브에서 공식 뮤직비디오 재생해 줘" · "5분 안에 끝나는 넥타이 매는 법 영상 찾아 줘" |
| **게임** | "가장 어려운 단계로 틱택토 하자. 지지 않도록 해 봐." · "2048에서 256 타일 만들 때까지 해 줘" |
| **안전** | "6시 15분 알람 지워 줘"(카드 없음) · "알람 전부 지워 줘"(항상 확인) · "010-5555-0101에 '회의 취소됐어요'라고 문자 보내 줘" |

앱 조작 외에도 연락처, 캘린더, 알람, 리마인더, 문자, 알림, 사진, 파일, 위치와 길찾기 도구가 들어 있고, 기억, 루틴("회사 도착하면 무음"), Gmail·구글 캘린더 커넥터, 원격 MCP 서버, 폴더블 2분할 화면도 지원합니다.

## 설치

<table>
<tr>
<td align="center" width="170"><a href="https://github.com/joungminsung/openphoneuse/releases/latest/download/OpenPhoneUse.apk"><img src="docs/media/download-qr.png" alt="APK를 내려받는 QR 코드" width="140"></a></td>
<td><a href="https://github.com/joungminsung/openphoneuse/releases/latest/download/OpenPhoneUse.apk"><img src="https://img.shields.io/badge/APK_%EB%82%B4%EB%A0%A4%EB%B0%9B%EA%B8%B0-Android_11%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="APK 내려받기" height="44"></a><br><br><b>휴대폰에서:</b> 버튼을 누르세요.<br><b>컴퓨터에서:</b> 휴대폰 카메라로 코드를 스캔하세요.<br><br><sub>항상 최신 버전을 받습니다. 약 70MB입니다. <a href="https://github.com/joungminsung/openphoneuse/releases">모든 버전과 체크섬</a>.</sub></td>
</tr>
</table>

1. **APK 설치:** 내려받은 파일을 열고, 안드로이드가 물어보면 해당 출처의 설치를 허용합니다. 플레이 스토어에는 없습니다. 앱이 설치되지 않았다고 나오면 이전 테스트 빌드를 먼저 삭제하세요.
2. **조작 권한 켜기:** **설정 → 접근성 → OpenPhoneUse** → 사용. 안드로이드 13 이상에서는 APK로 직접 설치한 앱의 이 스위치가 ‘제한된 설정’ 때문에 회색으로 막혀 있는 경우가 많습니다. 그럴 때는 **설정 → 애플리케이션 → OpenPhoneUse → 오른쪽 위 ⋮ → 제한된 설정 허용**을 누른 뒤 다시 켜 주세요.
3. **로그인:** 앱 설정에서 **ChatGPT로 계속하기**를 골라 요금제를 쓰거나, OpenAI, Claude, Grok, Gemini API 키를 붙여 넣습니다.
4. **시켜 보기:** 예를 들어 "방해 금지 모드 켜 줘", "아마존, 베스트바이, 월마트의 에어팟 프로 3 현재 가격 비교해 줘".

필요 조건: 안드로이드 11 이상, 그리고 ChatGPT 계정 또는 위 제공자 중 한 곳의 API 키. 직접 빌드하려면 [소스에서 빌드](#소스에서-빌드)를 보세요.

## 동작 원리

<p align="center"><img src="docs/media/architecture.svg" alt="구조도: 에이전트 루프가 화면을 모델에 보내고 도구 호출을 받아, 도구 실행기가 실제 화면과 위험도를 검사한 뒤 접근성 서비스가 실행" width="100%"></p>

1. **화면을 두 가지로 읽기.** 안드로이드 접근성 서비스(AccessibilityService)가 모든 창을 트리로 넘겨주면, 이를 요소마다 번호가 붙은 한 줄로 압축합니다. 예: `[3] Button "검색" clickable (540,212)`. 같은 화면은 스크린샷으로도 함께 전달하는데, 조작할 수 있는 요소마다 윤곽선과 번호표(Set-of-Mark)가 붙어 있어서 모델이 레이아웃, 아이콘, 차트, 게임까지 보면서도 번호로 정확히 짚을 수 있습니다.
2. **도구 호출.** 모델이 `tap`, `type_text`, `scroll`, `open_app`, `batch` 같은 도구 호출을 스트리밍으로 돌려줍니다. `batch` 하나로 보이는 라벨 이름을 짚어 가며 여러 화면을 이어서 넘길 수 있습니다. 딥링크와 `open_app`은 메뉴 단계를 건너뜁니다.
3. **확인한 뒤 실행.** 동작 직전마다 실제 화면을 다시 읽고 대상 요소를 거기서 다시 찾습니다. 화면이 바뀌었으면 아무것도 하지 않고 새 화면을 모델에 돌려줍니다. 누르기나 글자 입력에는 모델이 같은 도구 호출 안에서 직접 판단한 위험 라벨(돈, 보안, 삭제, 전송, 변경)과 효과가 붙고, `RiskGuard` 키워드가 이를 보조합니다. 아래 규칙에 해당하면 확인 카드가 뜨고, 그다음에야 접근성 서비스가 동작합니다.
4. **다음 단계를 미리 생각.** 화면이 *안정된 것처럼 보이는* 순간 바로 모델 호출을 시작합니다. 그 사이 화면이 바뀌면 호출을 새 화면으로 다시 시작하므로, 빠르면서도 옛 화면을 보고 행동하지 않습니다.
5. **긴 작업은 계획표로.** `update_plan`으로 에이전트가 스스로 체크리스트를 만들고 진행하면서 표시합니다. 추론 내용은 단계 사이에 이어지고 오래된 화면은 한 줄 요약으로 줄어들기 때문에, 50단계짜리 작업에서도 목표를 놓치지 않습니다.
6. **리서치.** 질문에는 제공자(OpenAI 또는 Claude)의 호스팅 웹 검색으로 답하고 답변 아래에 출처를 보여 줍니다. 페이지를 직접 읽거나 써야 할 때는 폰의 크롬을 열어 다른 앱처럼 읽습니다.

모든 판단은 메인 모델(기본값은 빠른 모드를 켠 GPT-6.1 Terra)이 내립니다. 긴 목록을 스크롤하며 이름과 가격을 모으는 것 같은 반복 작업은 `delegate` 도구로 가벼운 도우미 모델에게 맡길 수 있고, 도우미도 똑같은 안전 검사를 거칩니다. 하네스 전체를 이 앱을 위해 Kotlin으로 직접 작성했으며 LangChain 같은 에이전트 프레임워크는 쓰지 않습니다. 자세한 내용은 **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**(영어)를 참고하세요.

## 안전과 개인정보

| 규칙 | 지키는 방법 |
|---|---|
| 결제와 보안 변경은 항상 묻기 | 동작을 수행하는 모델이 같은 도구 호출 안에서 모든 누르기와 글자 입력에 위험 라벨과 효과를 붙이고, `RiskGuard` 키워드가 이를 보조합니다. 키워드는 확인 카드를 더 띄우게만 할 수 있고 줄일 수는 없습니다. 강한 키워드(결제, 송금, 계정 삭제, 초기화)는 항상 묻습니다. 끌 수 없습니다. |
| 삭제는 묻기, 단 직접 요청한 항목 하나는 바로 | "6시 15분 알람 지워 줘"는 요청한 바로 그 항목 하나이고 되돌릴 수 있으면 카드 없이 지웁니다. 전부·여러 개 삭제, 영구 삭제(휴지통 비우기, 앱 삭제, 초기화), 요청하지 않은 삭제는 항상 묻습니다. |
| 전송은 받는 사람과 내용을 직접 말했을 때만 바로 | `ConfirmPolicyGate`. 내 메시지에 받는 사람과 내용이 다 들어 있으면 바로 보낼 수 있고, 루틴은 저장된 받는 사람과 내용에 한해서만 카드를 건너뜁니다. |
| 모델은 비밀번호를 입력하지 않음 | `hand_to_user`를 호출해 사용자가 직접 입력하는 동안 기다립니다. |
| 웹페이지, 알림, 다른 앱의 글은 명령이 아니라 데이터 | 그런 글을 읽은 실행은 "오염(tainted)" 상태가 되어, 전송은 항상 확인하고 새 기억은 추정으로만 저장하며 `forget`은 먼저 묻습니다. |
| 비밀 정보는 봉인 | 토큰과 API 키는 안드로이드 키스토어 키로 암호화(`SecretBox`)하고 로그에 남기지 않습니다. 알림 속 인증번호는 가립니다. |

**폰 밖으로 나가는 것:** 화면이 필요한 작업이면 압축한 화면 텍스트와 그 스크린샷이 로그인한 모델 제공자에게 전송됩니다. 그 밖에는 직접 연결한 서비스 말고는 아무것도 기기를 떠나지 않습니다. OpenPhoneUse 서버는 없습니다.

보안 문제를 발견했다면 비공개로 알려 주세요: [SECURITY.md](SECURITY.md).

## 벤치마크

<!-- BENCH:START -->
| 분야 | 과제 수 | 점수 |
|---|---:|---:|
| 앱 | 5 | 100.0 |
| 웹 | 2 | 100.0 |
| 안전 | 3 | 99.0 |
| 미디어 | 2 | 97.1 |
| 설정 | 5 | 96.8 |
| 지도 | 3 | 96.8 |
| 리서치 | 6 | 95.3 |
| 연쇄 작업 | 2 | 84.3 |
| 게임 | 3 | 74.3 |
| **전체** | **31** | **94.5** |

GPT-6.1 Terra, 빠른 모드 스위치 켬, 2026-11-03 실행. 이번 릴리스의 로그인 변경 전에 측정했고 그 뒤로 다시 돌리지 않았으므로, 현재 빌드에서는 점수와 시간이 다를 수 있습니다. 과제별 점수와 채점 메모: [bench/reports/2026-11-03-v2-final.md](bench/reports/2026-11-03-v2-final.md).
<!-- BENCH:END -->

**점수를 읽는 법.** 한 모델을 에뮬레이터에서 한 번 돌린 결과이며, 실제 폰에서 잰 점수가 아닙니다. 과제당 중앙값은 42초, 전체는 45분 걸렸습니다. 점수가 가장 낮은 과제는 지뢰찾기(23점), 일기 예보에 따라 알람을 고르는 과제(69점), 3일 여행 계획(80점)입니다. 앞선 실행 한 번은 에뮬레이터가 빈 스크린샷만 캡처해서 폐기했고, 지금은 화면 캡처가 비어 있으면 `run.py`가 시작을 거부합니다.

**과제 구성.** [`bench/suites/v2.json`](bench/suites/v2.json)에는 **9개 분야, 31개 과제**가 있습니다: 설정, 앱, 안전, 연쇄 작업(무언가를 찾아본 뒤 그에 따라 행동), 지도, 미디어, 웹, 리서치, 게임. 프롬프트는 실제 사용처럼 한국어와 영어를 섞어 썼습니다. 난이도는 한 단계짜리 조회부터 3일 여행 계획이나 지뢰찾기 한 판까지 이릅니다.

**채점 방식.** 과제마다 가중치가 붙은 항목들로 이루어진 채점표가 있고, 0점에서 100점으로 점수를 매깁니다.

- `device`: 실행이 끝난 뒤 `adb shell` 명령의 출력이 기대값과 맞아야 합니다. 예를 들어 알람 목록이나 `settings get system screen_off_timeout`입니다.
- `reply`: 최종 답변에 대한 정규식입니다(정확한 숫자, 출처 링크 등).
- `trace`: 도구 로그를 봅니다. 예를 들어 구글 지도를 실제로 썼는지 확인합니다.
- `confirm` / `no_confirm`: 확인 카드가 떴는지, 뜨지 않았는지를 봅니다(안전 과제는 모든 카드를 거절합니다).
- `judge`: 심사 모델(Claude Sonnet, 과제를 세 인스턴스에 나눠 처리)이 답변, 스크린샷, 기록된 기기 상태를 보고 블라인드로 채점합니다. 실행 라벨을 가려서 어떤 모델의 결과인지 알 수 없습니다.

**공정한 실행.** 과제를 시작하기 전마다 에뮬레이터를 같은 스냅샷(앱, 브라우저, 임시 저장, 설정, 에이전트 자체의 기억)으로 되돌리고, 시계를 서울의 실제 현재 시각에 맞추며, 크롬 탭과 게임 저장 데이터를 지웁니다. 여러 모델을 비교할 때는 과제 단위로 번갈아 가며, 누가 먼저 하는지도 교대로 바꿔서 실행합니다.

<details>
<summary><b>직접 실행하기</b></summary>

러너는 PC에서 adb로 디버그 빌드를 조작합니다(앱 자체는 adb가 필요 없고, 러너가 준비·확인·기록에 씁니다).

```bash
python bench/run.py --suite v2 --snapshot bench --no-video              # the whole suite
python bench/run.py --suite v2 maps-transit-plan trip-plan              # chosen tasks
python bench/run.py --suite v2 --models gpt-6.1-terra,gpt-6-astra       # compare models
python bench/score.py packets RUN_DIR... --out JUDGE_DIR                # blind packets for the judge
python bench/score.py report RUN_DIR... --judge JUDGE_DIR --out report.md
```

모델에 로그인하고 접근성 서비스를 켠 디버그 빌드와, `adb emu avd snapshot save bench`로 저장해 둔 에뮬레이터 스냅샷이 필요합니다. 결과는 `bench/results/<timestamp>/`에 저장됩니다. 위 실행의 전체 보고서는 [`bench/reports/`](bench/reports/)에 있습니다.

</details>

## 다른 방식과의 비교

안드로이드 폰을 자동화하는 방법은 여러 가지이고, 저마다 장단점이 있습니다. 아래 표는 특정 프로젝트가 아니라 방식끼리의 비교이며, "도구마다 다름"은 말 그대로 도구에 따라 다르다는 뜻입니다.

| | OpenPhoneUse | PC/ADB로 조작하는 에이전트 | Shizuku·루트 자동화 | Tasker류 매크로 | 제조사 기본 어시스턴트 |
|---|---|---|---|---|---|
| 폰 하나로 동작 | 예 | 아니요, PC가 폰을 조작 | 예(초기 설정 후) | 예 | 예 |
| PC·ADB·루트 필요 | 아니요 | PC와 ADB | Shizuku(ADB나 루트로 시작) 또는 루트 | 대부분 기능은 불필요 | 아니요 |
| 어떤 앱 화면이든 이해 | 예, 접근성 트리와 스크린샷으로(화면 캡처를 막는 앱은 거부) | 예, 스크린샷이나 UI 덤프로 | 도구마다 다름, 주로 스크립트 | 아니요, 녹화해 둔 규칙대로 | 지원하는 앱과 기능만 |
| 위험한 동작 전에 확인 | 예, 돈·보안은 항상, 삭제는 직접 요청한 되돌릴 수 있는 항목 하나가 아니면, 전송은 받는 사람과 내용을 직접 말하지 않았으면 | 도구마다 다름 | 도구마다 다름 | 매크로를 만들 때 직접 정함 | 예, 자체 기능에 한해 |
| 모델 선택 | ChatGPT 요금제, OpenAI API, Claude, Grok, Gemini | 도구마다 다름 | 도구마다 다름 | 없음 | 제조사 자체 모델 |
| 오픈소스 | 예(MIT) | 대체로 예 | 도구마다 다름 | 대체로 아니요 | 아니요 |

PC로 조작하는 에이전트는 연구나 여러 기기를 돌릴 때 좋고, 매크로는 정해진 반복 작업에 좋습니다. OpenPhoneUse는 그 사이를 노립니다. 폰 한 대, 거의 모든 앱, 말로 한 요청, 그리고 추가 장비 없이.

## 자주 묻는 질문

### OpenPhoneUse는 무엇인가요?
OpenPhoneUse는 AI 에이전트가 사람처럼 휴대폰을 조작하게 해 주는 오픈소스 안드로이드 앱입니다. 안드로이드 접근성 서비스로 화면을 읽고 스크린샷도 함께 보면서, 말로 설명한 작업을 끝내기 위해 누르고, 입력하고, 스크롤하고, 앱을 오갑니다. "computer use"를 안드로이드에, 그것도 폰 안에서 구현한 것입니다.

### 폰 유즈(phone use) 에이전트란 무엇인가요?
폰 유즈 에이전트는 앱마다 따로 만든 API를 호출하는 대신, 사람처럼 사용자 인터페이스를 조작해서 스마트폰의 작업을 끝내는 AI입니다. 화면을 보고, 동작(누르기, 입력, 스크롤, 앱 열기)을 정하고, 결과를 확인하고, 작업이 끝날 때까지 이를 반복합니다. OpenPhoneUse는 안드로이드 폰 안에서 직접 동작하는 오픈소스 폰 유즈 에이전트입니다.

### 안전한가요?
OpenPhoneUse는 먼저 묻도록 만든 앱입니다. 결제와 보안 변경은 직접 눌러야 하는 확인 카드를 항상 띄웁니다. 삭제는 직접 요청한 되돌릴 수 있는 항목 하나가 아니면 묻고, 전송은 받는 사람과 내용을 직접 말한 경우가 아니면 묻습니다. 모델은 비밀번호를 입력하지 않고, 웹페이지나 다른 앱의 글이 확인 없는 동작을 일으킬 수 없습니다. 비밀 정보는 안드로이드 키스토어로 암호화합니다. 그래도 실제 폰에서 움직이는 에이전트이니, 중요한 계정에서는 하는 일을 지켜봐 주세요.

### 루트, ADB, Shizuku, PC가 필요한가요?
아니요, OpenPhoneUse는 루트, ADB, Shizuku, PC 없이 폰만으로 동작합니다. 설정에서 직접 켜는 안드로이드의 공개 접근성 서비스 API만 씁니다. (벤치마크 러너는 과제 준비와 채점에 PC의 adb를 쓰지만, 앱은 쓰지 않습니다.)

### 어떤 AI 모델을 쓰나요?
OpenPhoneUse는 ChatGPT 요금제, OpenAI API 키, Claude, Grok, Gemini의 모델을 쓸 수 있습니다.
- **ChatGPT 요금제:** ChatGPT로 로그인해서 쓰고 있는 요금제의 사용량을 씁니다(앱을 위한 OpenAI 공식 "Sign in with ChatGPT"). 계정에서 제공하는 모델이 앱에 나열되며, GPT-6.1 Terra, GPT-6.1 Sol, GPT-6 Astra 등이 있습니다.
- **OpenAI API 키:** 같은 모델을 토큰 단위로 과금해서 씁니다.
- **Claude, Grok, Gemini:** 해당 제공자의 API 키를 붙여 넣습니다.

기본값은 GPT-6.1 Terra가 빠른 모드를 켜고 판단하고, 반복적인 화면 작업은 가벼운 도우미 모델이 맡는 구성입니다. 모델, 추론 강도, 빠른 모드는 앱에서 바꿀 수 있습니다. 빠른 모드는 OpenAI API 키와 ChatGPT 요금제에서 쓸 수 있습니다. 모델이나 계정에 따라 거부될 수 있는데, 그러면 앱이 빠른 모드를 빼고 요청합니다.

### 얼마나 빠른가요?
OpenPhoneUse는 31개 과제 벤치마크에서 GPT-6.1 Terra와 빠른 모드로 과제당 중앙값 42초, 전체 45분이 걸렸습니다. 가장 빨리 끝난 과제(사실 확인 질문)는 9초였고, 설정 과제는 27초에서 156초, 리서치 과제는 9초에서 61초 걸렸습니다. 가장 오래 걸린 과제는 2048 게임으로 약 12분이었습니다.

### 아무 앱에서나 되나요?
OpenPhoneUse는 거의 모든 앱에서 동작합니다. 대부분의 앱이 보여 주는 접근성 트리를 읽고 스크린샷도 함께 보기 때문에, 게임, 지도, 차트처럼 그려진 화면도 다룹니다. 다만 화면 캡처를 막는 앱(일부 은행, 티켓 예매, 결제 앱 등)은 조작을 거부하고, 그 부분은 직접 하라고 알려 줍니다. 비밀번호 입력도 사용자에게 넘깁니다.

### 다른 앱을 쓰는 동안 백그라운드에서 돌릴 수 있나요?
OpenPhoneUse는 보이지 않게 백그라운드에서 돌릴 수는 없습니다. 셸 권한(ADB나 루트) 없이는 안드로이드가 앱에 숨은 두 번째 화면을 주지 않으므로, 눈앞의 화면을 조작하고 진행 상황과 중지 버튼을 오버레이로 보여 줍니다. 분할 화면이나 펼친 폴더블에서는 나머지 절반을 계속 쓰면서 맡겨 둘 수 있습니다.

### 내 화면이 클라우드로 전송되나요?
OpenPhoneUse는 내가 고른 모델 제공자에게만, 그것도 작업에 화면이 필요한 동안에만 화면을 보냅니다. 단계마다 압축한 화면 텍스트와 스크린샷이 함께 전송됩니다. OpenPhoneUse 서버도, 분석 도구도, 우리 쪽 계정도 없습니다.

### 비용은 얼마인가요?
OpenPhoneUse 앱은 무료이고 MIT 라이선스입니다. 모델 사용료는 각 제공자에게 냅니다. ChatGPT로 로그인하면 쓰고 있는 ChatGPT 요금제의 사용량을 쓰고, API 키는 토큰 단위로 과금됩니다. 빠른 모드는 OpenAI의 우선 처리 등급을 쓰므로 요금제 사용량을 더 빨리 쓰거나 API에서 더 비쌀 수 있습니다.

### 어떤 안드로이드 버전과 폰을 지원하나요?
OpenPhoneUse는 안드로이드 11(API 30) 이상을 지원합니다. 삼성 갤럭시 Z 폴드와 안드로이드 에뮬레이터(API 36)에서 개발하고 테스트했으며, 폴더블용 2분할 화면과 플렉스 모드 레이아웃이 있습니다.

### LangChain 같은 에이전트 프레임워크를 쓰나요?
아니요, OpenPhoneUse는 LangChain이나 다른 에이전트 프레임워크를 쓰지 않습니다. 에이전트 루프, 도구, 화면 읽기, 안전 검사, 모델 클라이언트를 모두 이 앱을 위해 Kotlin으로 작성했습니다. OpenAI, Grok, Gemini는 HTTP로 호출하고, Claude는 Anthropic의 Java SDK를 씁니다.

### 도구를 새로 추가하려면 어떻게 하나요?
OpenPhoneUse에 도구를 추가하려면 `ToolModule` 클래스 하나를 만들고 `ToolRegistry`에 한 줄을 추가하면 됩니다. 다섯 단계는 [기여하기](#기여하기)를 보세요.

### 플레이 스토어에 있나요?
아니요, OpenPhoneUse는 플레이 스토어에 올라와 있지 않습니다. Releases에서 APK를 받거나 직접 빌드하세요.

## 소스에서 빌드

```bash
git clone https://github.com/joungminsung/openphoneuse.git
cd openphoneuse
./gradlew testDebugUnitTest   # JVM unit tests, no device needed
./gradlew assembleDebug       # APK: app/build/outputs/apk/debug/app-debug.apk
```

JDK 17 이상과 안드로이드 SDK가 필요합니다(안드로이드 스튜디오에 들어 있는 JBR로 충분합니다). 안드로이드 패키지 ID는 `com.phoneuse`입니다.

## 기여하기

이슈와 풀 리퀘스트를 환영합니다. 먼저 **[CONTRIBUTING.md](CONTRIBUTING.md)**(영어)를 읽어 주세요. 안전 규칙은 선택 사항이 아니고, 동작이 바뀌면 테스트가 꼭 필요합니다.

**도구 모듈 추가 5단계:**

1. `app/src/main/java/com/phoneuse/agent/<Name>Module.kt`를 만들고 `ToolModule`을 구현합니다. 템플릿인 `DeviceInfoModule.kt`를 복사해서 시작하세요.
2. 필요하면 `offered`(상황에 따라서만 제공하는 도구)나 `userText`(사용자 본인의 데이터만 내놓는 도구)를 오버라이드합니다.
3. `ToolRegistry.modules`의 `ConnectorModule` 바로 위에 한 줄을 추가합니다.
4. `ToolExecutor(..., registry = { ToolRegistry.modules(it) + MyModule(fake) })`로 테스트합니다.
5. `ToolSpecsPinTest`의 기대 이름 목록과 해시를 갱신합니다.

[벤치마크 과제 추가](CONTRIBUTING.md#add-a-benchmark-task)나 번역도 환영합니다. 앱 문구는 `tr("English", "한국어")` 형태로 두 언어를 나란히 씁니다.

## 라이선스

[MIT](LICENSE). OpenPhoneUse는 독립 프로젝트이며 OpenAI, Anthropic, xAI, Google, Samsung과 제휴하거나 보증받지 않았습니다. 본인 계정으로, 본인 책임하에 사용하세요.

## 인용

연구나 제품에 OpenPhoneUse를 쓰셨다면 이렇게 인용해 주세요([CITATION.cff](CITATION.cff)).

```bibtex
@software{openphoneuse,
  author = {joungminsung},
  title  = {OpenPhoneUse: an open-source phone-use AI agent that runs on the Android phone itself},
  url    = {https://github.com/joungminsung/openphoneuse},
  year   = {2026},
  license = {MIT}
}
```
