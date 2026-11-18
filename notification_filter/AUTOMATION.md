# Schedule automation

Change your notification schedule from Tasker, or from a shell with `adb`. The app receives one broadcast, checks it, and writes the schedule through the same path as the editor.

It is off until you turn it on in **Notification settings → Schedule automation**. Turning it on creates a credential (64 hex characters). Every request must carry it.

## Set up

1. Open **Notification settings → Schedule automation** and turn on **Allow automation**.
2. Tap **Copy** (or **Show**). Keep the credential private. **Rotate** replaces it; **Revoke** deletes it and turns automation off.
3. In Tasker, add a **Send Intent** action:

| Field | Value |
| --- | --- |
| Action | `ly.com.tahaben.notification_filter.action.SET_SCHEDULE` |
| Extra | `credential:%FARHAN_CREDENTIAL` |
| Extra | `payload:%FARHAN_PAYLOAD` |
| Package | `ly.com.tahaben.farhan_alpha` |
| Class | `ly.com.tahaben.notification_filter_data.automation.ScheduleAutomationReceiver` |
| Target | Broadcast Receiver |

Store the credential in a Tasker variable that is not shared or logged, and build the JSON in `%FARHAN_PAYLOAD` before the action (Tasker splits an Extra at its first colon, so JSON in a variable is safest). The app only reads those two string extras. Android does not deliver implicit custom broadcasts to manifest receivers, so Package or Class is required.

Check the same request from a computer first (placeholders, not real values):

```sh
adb shell am broadcast \
  -n ly.com.tahaben.farhan_alpha/ly.com.tahaben.notification_filter_data.automation.ScheduleAutomationReceiver \
  -a ly.com.tahaben.notification_filter.action.SET_SCHEDULE \
  --es credential '<CREDENTIAL>' \
  --es payload '{"version":1,"windows":[{"start":0,"end":420,"budget":0},{"start":420,"end":1440,"budget":12}]}'
```

`am broadcast` prints `result=-1, data="accepted"` on success and `result=0, data="rejected:<ERROR>"` on failure.

## Payload

One JSON object, version 1.

| Field | Type | Meaning |
| --- | --- | --- |
| `version` | integer | Must be `1`. |
| `mode` | string, optional | `"repeating"` (default) or `"dated"`. |
| `date` | string | Required for `"dated"`, forbidden for repeating. ISO local date, `YYYY-MM-DD`. |
| `windows` | array, 1 to 24 | The whole day, in order. |
| `windows[].start` | integer | Minute of the day the window starts, 0 to 1439. |
| `windows[].end` | integer | Minute it ends, 1 to 1440. 1440 is 24:00. |
| `windows[].budget` | integer | Points for the window, 0 or more. Any size is accepted. |

The windows must start at 0, end at 1440, touch with no gap or overlap, and each last at least 60 minutes. Numbers must be whole JSON integers: `5.0`, `"5"` and `true` are refused. Unknown fields are refused.

Examples:

```json
{"version":1,"windows":[{"start":0,"end":420,"budget":0},{"start":420,"end":1440,"budget":12}]}
```

```json
{"version":1,"mode":"dated","date":"2026-11-20","windows":[{"start":0,"end":600,"budget":0},{"start":600,"end":1440,"budget":10}]}
```

## What it changes

- **Repeating** replaces the every-day schedule. It also removes today's one-date schedule, so your change is not hidden. One-date schedules for future dates stay.
- **Dated** sets the schedule for that one date. Other dates use the every-day schedule. Dates before today are dropped whenever any schedule is saved.
- Points already spent in the current window are kept. Sending the same request again does not give them back.
- If you and automation both change a schedule, the last accepted change wins.
- A request that is refused changes nothing.

## Answers

- **Ordered broadcast** (`adb`, or Tasker when it waits for a result): result code `-1` and data `accepted`, or code `0` and data `rejected:<ERROR>`.
- **Settings screen**: the line under Schedule automation shows the last request, its time, and the error name. It never shows your credential or payload.

| Error | Meaning |
| --- | --- |
| `DISABLED` | Automation is off. Nothing is recorded. |
| `BAD_CREDENTIAL` | Wrong, missing or revoked credential. |
| `BUSY` | Two requests were already being handled. Nothing was read or changed. Wait a few seconds and send it again. |
| `PAYLOAD_TOO_LARGE` | More than 4096 characters (at most 12 KiB as UTF-8), or more than 24 windows. |
| `MALFORMED_PAYLOAD` | Not the JSON above, or an unknown field. |
| `UNSUPPORTED_VERSION` | `version` is missing or not `1`. |
| `UNKNOWN_MODE` | `mode` is not `repeating` or `dated`. |
| `DATE_REQUIRED`, `MALFORMED_DATE`, `DATE_NOT_ALLOWED` | Date missing for dated, not a real date, or given for repeating. |
| `MALFORMED_WINDOW` | A window lacks a whole-number `start`, `end` or `budget`. |
| `INVALID_SCHEDULE` | Gap, overlap, a window under 60 minutes, or the day is not covered. |
| `STORAGE_FAILED` | Saving failed or the saved schedule needs repair. Nothing was changed. |

## Limits

- At most two requests are handled at once. Extra requests get `BUSY` straight away and are not queued, so retry them.
- The credential is a local key, not a login. Anyone who has it can change your schedule. It is not written to logs.
