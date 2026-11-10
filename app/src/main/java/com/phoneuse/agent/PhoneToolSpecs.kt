package com.phoneuse.agent

import com.phoneuse.model.ToolSpec

/** [PhoneModule]'s tools, kept apart from its logic because the descriptions are long. */
internal object PhoneToolSpecs {
    val all: List<ToolSpec> = listOf(
        ToolSpec(
            "get_location",
            "The phone's current location: {lat, lng, accuracy_m, address?}. Use it for \"near me\" questions.",
            schema(),
        ),
        ToolSpec(
            "geocode",
            "Find coordinates of a place name or address (OpenStreetMap). Returns up to 5 [{name, lat, lng, address, distance_m?}], " +
                "nearest to near_lat/near_lng first when given.",
            schema(
                required = listOf("query"),
                props = mapOf(
                    "query" to prop("string", "Place name or address, e.g. \"스타벅스 역삼역점\"."),
                    "near_lat" to prop("number", "Search around this latitude (optional)."),
                    "near_lng" to prop("number", "Search around this longitude (optional)."),
                ),
            ),
        ),
        ToolSpec(
            "open_route",
            "Open public-transport directions to a place in the installed map app (Naver Map, else Kakao Map, else Google Maps), " +
                "then read the route from the screen.",
            schema(
                required = listOf("lat", "lng", "name"),
                props = mapOf(
                    "lat" to prop("number", "Destination latitude."),
                    "lng" to prop("number", "Destination longitude."),
                    "name" to prop("string", "Destination name."),
                ),
            ),
        ),
        ToolSpec(
            "remind_at",
            "Schedule a one-time app notification (not an alarm clock alarm) with the given text.",
            schema(
                required = listOf("time", "text"),
                props = mapOf(
                    "time" to prop("string", "HH:mm (next occurrence) or ISO 8601 local date-time, e.g. 2026-11-02T08:30."),
                    "text" to prop("string", "What the notification says, in Korean."),
                ),
            ),
        ),
        ToolSpec(
            "find_contact",
            "Find contacts on the phone by name. Returns up to 10 [{name, phones[], emails[]}].",
            schema(required = listOf("name"), props = mapOf("name" to prop("string", "Name or part of it, e.g. \"민수\"."))),
        ),
        ToolSpec(
            "call",
            "Place a phone call. The user confirms on a card first unless their own message asked to call this person or " +
                "number. The call screen then takes over: finish with stay_in_app=true.",
            schema(required = listOf("to"), props = mapOf("to" to prop("string", "Phone number, or the contact's name as the user said it (e.g. \"엄마\")."))),
        ),
        ToolSpec(
            "calendar_list",
            "Events in the phone's calendars that overlap from–to: [{id, title, start, end, location?, all_day?}], by start.",
            schema(
                required = listOf("from", "to"),
                props = mapOf(
                    "from" to prop("string", "ISO 8601 local date or date-time, e.g. 2026-11-03 or 2026-11-03T09:00."),
                    "to" to prop("string", "ISO 8601; a date alone means the end of that day. At most 62 days after from."),
                ),
            ),
        ),
        ToolSpec(
            "calendar_add",
            "Add a calendar event. Without confirmed it saves nothing: it shows an event preview card with overlapping events. " +
                "When the user then confirms (the card's [그대로 저장]), call again with the same values and confirmed=true " +
                "as the first tool of that turn.",
            schema(
                required = listOf("title", "start", "end"),
                props = mapOf(
                    "title" to prop("string", "Event title."),
                    "start" to prop("string", "ISO 8601 local date-time (2026-11-03T14:00), or a date for an all-day event."),
                    "end" to prop("string", "ISO 8601 like start. For an all-day event, the last day."),
                    "location" to prop("string", "Place (optional)."),
                    "reminder_min" to prop("integer", "Notify this many minutes before (optional)."),
                    "confirmed" to prop("boolean", "true only to save what the user confirmed on the latest event card."),
                ),
            ),
        ),
        ToolSpec(
            "next_alarm",
            "The next alarm set in the clock app, as HH:mm with its day, or none.",
            schema(),
        ),
        ToolSpec(
            "set_alarm",
            "Set an alarm in the clock app. The user confirms on a card first unless their own message asked for the alarm. " +
                "For \"wake me / 알람\" requests, not for reminders.",
            schema(
                required = listOf("hour", "minute"),
                props = mapOf(
                    "hour" to prop("integer", "0-23."),
                    "minute" to prop("integer", "0-59."),
                    "label" to prop("string", "Alarm name (optional)."),
                    "days" to arr(str(), "Repeat on these days: mon, tue, wed, thu, fri, sat, sun. Leave out for a one-time alarm."),
                ),
            ),
        ),
        ToolSpec(
            "set_timer",
            "Start a countdown timer in the clock app. The user confirms on a card first unless their own message asked for " +
                "the timer. For \"3분 타이머\" requests; an alarm at a clock time is set_alarm.",
            schema(
                required = listOf("seconds"),
                props = mapOf(
                    "seconds" to prop("integer", "Length in seconds, 1-86400 (3 minutes is 180)."),
                    "label" to prop("string", "Timer name (optional)."),
                ),
            ),
        ),
        ToolSpec(
            "photos_find",
            "Find photos in the phone's gallery, newest first: [{uri, taken_at, lat?, lng?}] and how many matched in all. " +
                "lat/lng appear when the photo location permission is granted. For a trip or a place (a country, city, island), " +
                "pass place (with from/to) instead of guessing from thumbnails; the reply gives the dates the matches were taken. " +
                "place is a rough box, so a neighbouring area may slip in. To see what is in photos, pass the uris to photos_look.",
            schema(
                props = mapOf(
                    "from" to prop("string", "ISO 8601 local date or date-time (optional)."),
                    "to" to prop("string", "ISO 8601; a date alone means the end of that day (optional)."),
                    "near" to obj(
                        "lat" to num(), "lng" to num(), "radius_km" to num(),
                        required = listOf("lat", "lng"),
                    ),
                    "place" to prop("string", "Place name where the photos were taken: a country, city or region, e.g. 영국, 런던, 제주도 (optional)."),
                    "album" to prop("string", "Folder (album) name, e.g. Camera, Screenshots (optional)."),
                    "limit" to prop("integer", "At most this many, up to 60 (default 60)."),
                ),
            ),
        ),
        ToolSpec(
            "photos_look",
            "Look at up to 24 photos (small thumbnails) and answer a question about them, naming photos by number in the uris order. " +
                "Use it to pick photos by what they show (sea, sunset, a person).",
            schema(
                required = listOf("uris", "question"),
                props = mapOf(
                    "uris" to arr(str(), "content://media/… uris from photos_find."),
                    "question" to prop("string", "What to look for, in Korean."),
                ),
            ),
        ),
        ToolSpec(
            "photos_album",
            "Make an album: copy photos into Pictures/<name>/ (originals stay). Without confirmed it only shows a photos card; " +
                "when the user confirms (its [앨범 만들기]), call again with confirmed=true.",
            schema(
                required = listOf("name", "uris"),
                props = mapOf(
                    "name" to prop("string", "Album name."),
                    "uris" to arr(str(), "content://media/… uris."),
                    "confirmed" to prop("boolean", "true only to make the album the user confirmed on the photo card."),
                ),
            ),
        ),
        ToolSpec(
            "set_wallpaper",
            "Set a photo as the home screen wallpaper (the user confirms on a card first).",
            schema(required = listOf("uri"), props = mapOf("uri" to prop("string", "content://media/… uri."))),
        ),
    )
}
