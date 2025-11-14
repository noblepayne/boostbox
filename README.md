<div align="center">
  <img src="./images/v4vbox.png" alt="3D box with 'V4V' on one side." width="128">
</div>

# BoostBox

A simple, lightweight, and self-hostable API for storing and retrieving [Podcasting 2.0](https://podcasting2.org/) payment metadata.

Demo: [boostbox.noblepayne.com](https://boostbox.noblepayne.com)

Demo Boost: [01K9RJ1ZDFE2GV7Z0VKDVT3V6M](https://boostbox.noblepayne.com/boost/01K9RJ1ZDFE2GV7Z0VKDVT3V6M)

______________________________________________________________________

## What is BoostBox?

BoostBox provides a simple API that implements a developing standard for [transmitting payment metadata via HTTP headers](https://github.com/Podcastindex-org/podcast-namespace/pull/734). It allows an application to:

1. Store the full JSON metadata payload with a single `POST` request.
1. Receive a short, stable URL in return.
1. Place this URL into the Lightning invoice description.

This ensures the link to the original boost metadata is preserved with the payment, enabling the full [Value4Value](https://podcasting2.org/docs/podcast-namespace/tags/value) experience.

## How It Works

The process is designed to be as simple as possible for podcast app developers.

### Step 1: POST Metadata to BoostBox

Your app gathers all the boostagram metadata and sends it as a JSON object to the `/boost` endpoint.

```sh
curl -X POST https://your-boostbox-instance.com/boost \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: v4v4me" \
  -d '{
    "action": "boost",
    "split": 1, 
    "value_msat": 639000,
    "value_msat_total": 639000,
    "timestamp": "2025-11-02T16:30:00Z",
    "app_name": "My Awesome Player",
    "sender_name": "Satoshi",
    "message": "Best episode ever!",
    "feed_guid": "72d5e069-f907-5ee7-b0d7-45404f4f0aa5",
    "feed_title": "LINUX Unplugged",
    "item_guid": "4c0a537d-10c6-40ca-b44c-9a43891313c6",
    "item_title": "639: The Mess Machine"
  }'
```

### Step 2: Get the Response

BoostBox stores the metadata and returns a URL and a pre-formatted BOLT11 description.

```json
{
  "id": "01K9R9E2JNE1CR0ME6CFM45T8E",
  "url": "http://localhost:8080/boost/01K9R9E2JNE1CR0ME6CFM45T8E",
  "desc": "rss::payment::boost http://localhost:8080/boost/01K9R9E2JNE1CR0ME6CFM45T8E Best episode ever!"
}
```

### Step 3: Pay the Invoice

Your app uses the `desc` field from the response as the description when paying the podcaster's Lightning Address invoice.

### Step 4: Metadata is Preserved

When the podcaster's receiving service (like Helipad, Alby, etc.) gets the payment, it can fetch the URL from the description. BoostBox will respond with the full, original metadata in the `x-rss-payment` HTTP header.

```sh
curl -v "http://localhost:8080/boost/01K9R9E2JNE1CR0ME6CFM45T8E"

> GET /boost/01K9R9E2JNE1CR0ME6CFM45T8E HTTP/1
> Host: your-boostbox-instance.com
...

< HTTP/1 200
< content-type: text/html; charset=UTF-8
< x-rss-payment: %7B%22message%22%3A%22Best%20episode%...
...

<!-- The page body will show a human-readable view of the metadata -->
```

## Getting Started

### Option 1: Run Directly with Nix (No Cloning)

If you have [Nix](https://nixos.org/download.html) installed with flakes support, you can run BoostBox directly without cloning the repository:

```sh
nix run github:noblepayne/boostbox
```

This will start the server on `http://localhost:8080` with default settings.

### Option 2: Build and Run Locally with Nix

1. Clone the repository: `git clone https://github.com/noblepayne/boostbox`
1. Change into the directory: `cd boostbox`
1. Build: `nix build`
1. Run: `./result/bin/boostbox`

Configure via environment variables (see Configuration section below).

### Option 3: Develop with Nix

For REPL-oriented development with Calva (VSCode):

1. Clone the repository: `git clone https://github.com/noblepayne/boostbox`
1. Change into the directory: `cd boostbox`
1. Enter the development environment: `./dev.sh`
1. VSCode will launch automatically with Calva pre-configured
1. Configure via environment variables (see Configuration section below)
1. Use Calva to connect to the NREPL and start developing

### Option 4: Docker

Build the container:

```sh
git clone https://github.com/noblepayne/boostbox && cd boostbox
nix build .#container && docker load < ./result
```

Then run with a `.env` file:

```sh
docker run -p 8080:8080 --env-file ./.env --name my-boostbox boostbox
```

Or use Docker Compose:

```sh
docker-compose up
```

Make sure to configure your `.env` file with required environment variables (see Configuration below).

## Configuration

Configuration is handled via environment variables.

| Variable | Required | Default | Description |
| -------------------- | :------: | --------------------- | ------------------------------------------------------------------------------------------------------- |
| `ENV` | No | `DEV` | The runtime environment: `DEV`, `STAGING`, or `PROD`. |
| `BB_PORT` | No | `8080` | The port the webserver will listen on. |
| `BB_BASE_URL` | No | `http://localhost:8080` | The public base URL of the service (e.g., `https://my-boostbox.com`). Used to construct response URLs. |
| `BB_ALLOWED_KEYS` | No | `v4v4me` | Comma-separated list of API keys clients must provide in the `X-Api-Key` header to use the `POST /boost` endpoint. |
| `BB_MAX_BODY` | No | `102400` | Maximum allowed size for request bodies in bytes (approximately 100KB by default). |
| `BB_STORAGE` | No | `FS` | The backend for storing metadata: `FS` (filesystem) or `S3`. Currently only `FS` is implemented. |
| `BB_FS_ROOT_PATH` | No | `boosts` | If `BB_STORAGE=FS`, the root directory where metadata files will be stored. |

**Note:** S3 storage is planned for a future release and is not yet implemented.

## API Quick Reference

### `POST /boost`

Stores boostagram metadata.

- **Authentication:** Requires an API key in the `X-Api-Key` header (default: `v4v4me`).

- **Request Body:** A JSON object with the following **required** fields:

  - `action` (enum: `boost` or `stream`)
  - `split` (number, min 0.0)
  - `value_msat` (integer, min 1)
  - `value_msat_total` (integer, min 1)
  - `timestamp` (ISO-8601 string)

  Optional fields include: `group`, `message`, `app_name`, `app_version`, `sender_id`, `sender_name`, `recipient_name`, `recipient_address`, `value_usd`, `position`, `feed_guid`, `feed_title`, `item_guid`, `item_title`, `publisher_guid`, `publisher_title`, `remote_feed_guid`, `remote_item_guid`, `remote_publisher_guid`.

- **Success Response (`201 Created`):**

  ```json
  {
    "id": "01K9R9E2JNE1CR0ME6CFM45T8E",
    "url": "https://your-boostbox-instance.com/boost/01K9R9E2JNE1CR0ME6CFM45T8E",
    "desc": "rss::payment::boost https://your-boostbox-instance.com/boost/01K9R9E2JNE1CR0ME6CFM45T8E Your message here"
  }
  ```

- **Error Responses:**

  - `400 Bad Request` - Invalid or missing required fields
  - `401 Unauthorized` - Missing or invalid `X-Api-Key` header
  - `413 Payload Too Large` - Request body exceeds `BB_MAX_BODY` limit

### `GET /boost/{id}`

Retrieves boostagram metadata.

- **Response:** Returns an HTML page for human-readable display. The full metadata JSON is also available in the `x-rss-payment` HTTP header (URL-encoded).
- **Status Codes:**
  - `200 OK` - Metadata found
  - `404 Not Found` - Unknown boost ID

### `GET /health`

A simple healthcheck endpoint for monitoring.

- **Response:** `{"status": "ok"}`

### Full API Documentation

A complete OpenAPI/Swagger specification can be viewed at the `/docs` endpoint of a running instance. The raw OpenAPI JSON is available at `/openapi.json`.

______________________________________________________________________

Built with Nix and Clojure. Licensed under the MIT License.
