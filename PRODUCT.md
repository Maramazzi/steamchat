# SteamChat product context

## What it is

SteamChat is a native Android client for Steam friends, profiles, direct messages, and Steam Chat groups. It uses Telegram Android only as the UI foundation; Steam and JavaSteam are the backend.

## Who it serves

Steam users who want a focused mobile chat client with real Steam identity, presence, direct-message history, and group channels.

## Product principles

- Show only real Steam data; never invent counts, members, messages, or presence.
- Keep realtime facts on the Steam CM path and use Steam Community only for public profile enrichment.
- Preserve the current Steam Modern Card visual language and Telegram navigation primitives.
- Make direct chats and groups equally easy to reach without creating parallel app shells.
- Prefer a useful text-chat MVP over speculative support for unsupported media transports.

## Current group-chat scope

- Existing Steam Chat groups only; creation, invitations, and role administration are deferred.
- Text channels, realtime messages, sending, read acknowledgement, and paginated history.
- One chronological inbox for direct chats and groups, with Telegram-style folder tabs.
- Device-only folders support creation, renaming, deletion and explicit chat/group membership, isolated per Steam account. No server sync.
- Opening a group shows a separate Discord-mobile-style navigator: a left group-avatar rail and text/voice channel sections. Text channels reuse the existing chat renderer; Back returns to channels.
- Group rows show real group/channel state and unread indicators available from the protocol.
- Voice-capable channels show their real participant count; tapping them only reports unsupported voice, without opening text history or joining voice.

## Technical boundaries

- UI: `TMessagesProj/src/main/kotlin/org/steamchat/ui`
- Domain models and `SteamService`: `steamchat-domain`
- JavaSteam transport and protobuf mapping: `steamchat-steamkit`
- No JavaSteam/protobuf types may cross into UI or domain.
- Existing compact Steam cells and fragments are extended; Telegram's monolithic chat/dialog cells are not forked.
