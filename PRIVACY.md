# Pulse Feed privacy policy

Pulse Feed is an offline-first feed reader. It has no user accounts, no analytics, no
crash reporters, no advertising, and no tracking SDKs. Nothing you read is reported to us,
because we operate no servers that could receive it.

## What stays on your device

- Feed subscriptions, folders, and read/bookmark state
- Stored article text and metadata, kept in a local SQLite database
- Settings, including appearance, refresh cadence, and notification preferences
- Credentials you choose to store for your own sync server, encrypted with the platform
  keystore where the host supports it

Deleting the app, or clearing its data, removes this local data.

## Network requests the app makes

Pulse Feed only contacts the servers you point it at:

- The feed and article URLs you subscribe to or open, to fetch content
- Images embedded in those articles, when you are viewing them
- Optional services you enable yourself, such as a self-hosted sync server or an AI
  provider you configure

Those third-party servers see the request, your IP address, and the app's HTTP user agent,
exactly as they would from any feed reader. Their own privacy policies govern that data.
No request carries a Pulse Feed account identifier, because there is none.

## On-device AI

Summaries and other AI features run locally against a model file you download. Prompts and
article text are not sent to any cloud service unless you explicitly configure a cloud
provider yourself, and the default configuration keeps processing local.

## Ad and tracker blocking

Where the host supports it (Android), Pulse Feed blocks known ad and tracker hosts inside
its in-app browser. The blocklist is bundled with the app; blocked requests are never sent.

## Backups

Android's system backup may include the app's local database, so read and saved state
survives a device change if you have backup enabled. You can disable this in Android's
settings for the app.

## Contact

Open an issue at <https://github.com/wongpinter/pulse-feed/issues> for any privacy question.