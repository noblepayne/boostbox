# BoostBox

<div align="center">
  <img src="./images/v4vbox.png" alt="3D box with 'V4V' on one side." width="128">
</div>

**A simple, lightweight, and self-hostable sidecar service for storing and serving [Podcasting 2.0](https://podcasting2.org/) payment metadata.**

BoostBox bridges the gap between **Lightning Address** payments (which have character limits) and rich **Value4Value metadata** (which can be large).

______________________________________________________________________

## Background & Motivation

While **Keysend** was the original method for Podcasting 2.0 payments, the **Lightning Address** has become the de-facto standard for sharing payment information because it is supported by popular wallets like Strike, CashApp, and Wallet of Satoshi.

**The Problem:**
Lightning Address payments use the BOLT11 invoice format, which effectively limits descriptions to ~200 characters. This is not enough space to carry the full rich JSON metadata (Sender info, Episode GUIDs, App version, custom messages) required for a full Value4Value experience.

**The Solution:**
As proposed by the [Podcasting 2.0 community](https://github.com/Podcastindex-org/podcast-namespace/pull/734), the solution is a "Sidecar" approach:

1. **Store** the heavy JSON metadata on a server (BoostBox).
1. **Generate** a short, stable URL.
1. **Embed** that URL into the Lightning invoice description using a specific prefix: `rss::payment::{action} {url} {truncated message}`.
1. **Serve** the full metadata via the `x-rss-payment` HTTP header when the receiving wallet checks that URL.

**BoostBox is the server implementation of this spec.** It allows any app developer or hobbyist to run their own metadata endpoint without needing to build a custom database or API.

______________________________________________________________________

## How It Works

### 1. Store the Metadata

Your Podcast App gathers the full boostagram metadata (Sender, App Name, Feed GUID, etc.) and `POST`s it to your BoostBox instance.

**Request:**

```sh
curl -X POST https://boostbox.noblepayne.com/boost) \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: v4v4me" \
  -d '{
    "action": "boost",
    "value_msat": 25000,
    "sender_name": "Satoshi",
    "message": "Great episode! This solved my node sync issues.",
    "feed_guid": "72d5e069-f907-5ee7-b0d7-45404f4f0aa5",
    "app_name": "PodTester",
    "timestamp": "2025-11-25T15:09:10.174Z"
  }'
```

### 2. Receive the Invoice Description

BoostBox saves the JSON to storage (Filesystem or S3) and returns a short URL and a pre-formatted description string ready for the Lightning Invoice.

**Response:**

```json
{
  "id": "01KB19TNRVE1RVQCXVFWY68PYG",
  "url": "https://boostbox.noblepayne.com/boost/01KB19TNRVE1RVQCXVFWY68PYG",
  "desc": "rss::payment::boost https://boostbox.noblepayne.com/boost/01KB19TNRVE1RVQCXVFWY68PYG Great episode!..."
}
```

### 3. Send the Payment

Your App sends the Lightning payment using the `desc` string from the response. The truncated message ensures the user sees context, while the URL points to the full data.

### 4. Metadata is Preserved

When the podcaster's wallet (e.g., Helipad, Alby) receives the payment, it detects the `rss::payment::` prefix, fetches the URL, and BoostBox returns the full metadata in the `x-rss-payment` header.

______________________________________________________________________

## Deployment Options

BoostBox is built with **Clojure** and **Nix**, making it incredibly lightweight and robust.

### 🐳 Docker (Quick Start)

The easiest way to run BoostBox.

**1. Create configuration:**

```bash
# For local filesystem storage
cp env.fs.template .env
# OR for S3 storage
cp env.s3.template .env
```

**2. Run container:**

```bash
docker run -p 8080:8080 --env-file .env --name boostbox ghcr.io/noblepayne/boostbox:latest
```

### 🐳 Docker Compose

If you prefer Compose, ensure your `.env` file is created (as above), then update the `image` in `docker-compose.yml` to `ghcr.io/noblepayne/boostbox:latest` and run:

```bash
docker-compose up
```

### ❄️ Nix (Run Directly)

If you have Nix installed with flakes:

```bash
nix run github:noblepayne/boostbox
```

### 🛠 Build from Source

```bash
git clone https://github.com/noblepayne/boostbox && cd boostbox
nix build
./result/bin/boostbox
```

______________________________________________________________________

## Configuration

Configuration is managed via environment variables.

### Core Settings

| Variable | Default | Description |
| :--- | :--- | :--- |
| `ENV` | `DEV` | `DEV`, `STAGING`, or `PROD` |
| `BB_PORT` | `8080` | HTTP listening port |
| `BB_BASE_URL` | `http://localhost:8080` | Public URL used to generate response links |
| `BB_ALLOWED_KEYS` | `v4v4me` | Comma-separated list of API keys for `POST` access |
| `BB_MAX_BODY` | `102400` | Maximum allowed size for request bodies in bytes (approx 100KB) |
| `BB_STORAGE` | `FS` | `FS` (Filesystem) or `S3` |

### Storage Backends

**Filesystem (`BB_STORAGE=FS`)**
Ideal for self-hosting or simple deployments.

| Variable | Default | Description |
| :--- | :--- | :--- |
| `BB_FS_ROOT_PATH` | `boosts` | Directory to store JSON files |

**S3 / MinIO (`BB_STORAGE=S3`)**
Ideal for production or scalable deployments.

| Variable | Description |
| :--- | :--- |
| `BB_S3_ENDPOINT` | The S3 endpoint URL (e.g. `https://s3.amazonaws.com` or `http://localhost:9000`). Must include protocol. |
| `BB_S3_REGION` | AWS region (e.g., `us-east-1`) |
| `BB_S3_ACCESS_KEY` | S3 access key ID |
| `BB_S3_SECRET_KEY` | S3 secret access key |
| `BB_S3_BUCKET` | S3 bucket name |

### S3 Setup Examples

**Using AWS S3:**

```sh
export BB_STORAGE=S3
export BB_S3_REGION=us-east-1
export BB_S3_ACCESS_KEY=your-aws-access-key
export BB_S3_SECRET_KEY=your-aws-secret-key
export BB_S3_BUCKET=my-boostbox-bucket
./result/bin/boostbox
```

**Using MinIO locally:**

```sh
export BB_STORAGE=S3
export BB_S3_ENDPOINT=http://localhost:9000
export BB_S3_REGION=us-east-1
export BB_S3_ACCESS_KEY=minioadmin
export BB_S3_SECRET_KEY=minioadmin
export BB_S3_BUCKET=boostbox
./result/bin/boostbox
```

______________________________________________________________________

## API Reference

### `POST /boost`

Stores metadata and returns the invoice description.

- **Headers:** `X-Api-Key` required.
- **Body:** A JSON object with the following **required** fields:
  - `action` (enum: `boost` or `stream`)
  - `split` (number, min 0.0)
  - `value_msat` (integer, min 1)
  - `value_msat_total` (integer, min 1)
  - `timestamp` (ISO-8601 string)
- **Optional fields:** `sender_name`, `feed_guid`, `app_name`, `message`, etc.
- **Returns:** `201 Created` with `id`, `url`, and `desc`.

**Example Success Response:**

```json
{
  "id": "01KB19TNRVE1RVQCXVFWY68PYG",
  "url": "[https://boostbox.noblepayne.com/boost/01KB19TNRVE1RVQCXVFWY68PYG](https://boostbox.noblepayne.com/boost/01KB19TNRVE1RVQCXVFWY68PYG)",
  "desc": "rss::payment::boost [https://boostbox.noblepayne.com/boost/01KB19TNRVE1RVQCXVFWY68PYG](https://boostbox.noblepayne.com/boost/01KB19TNRVE1RVQCXVFWY68PYG) Your message here"
}
```

**Error Responses:**

- `400 Bad Request` - Invalid or missing required fields
- `401 Unauthorized` - Missing or invalid `X-Api-Key` header
- `413 Payload Too Large` - Request body exceeds `BB_MAX_BODY` limit

### `GET /boost/{id}`

Retrieves metadata.

- **Response:**
  - **Body:** Human-readable HTML page showing the boost details.
  - **Header `x-rss-payment`:** URL-encoded JSON string containing the full metadata.
- **Status Codes:**
  - `200 OK` - Metadata found
  - `404 Not Found` - Unknown boost ID

### `GET /health`

A simple healthcheck endpoint for monitoring.

- **Response:** `{"status": "ok"}`

### `GET /docs`

Full OpenAPI/Swagger documentation.

______________________________________________________________________

## Development

BoostBox includes a full development environment via `devenv`.

```bash
# Start a REPL, PostgreSQL, MinIO, and other tools
./dev.sh
```

Built with ❤️ for the Podcasting 2.0 community.
Licensed under MIT.
