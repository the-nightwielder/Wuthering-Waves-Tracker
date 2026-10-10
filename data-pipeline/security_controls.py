from urllib.parse import urlsplit

MAX_SOURCE_RESPONSE_BYTES = 10 * 1024 * 1024


def is_verified_official_source(source, publisher_url):
    if source.get("category") != "official" or not publisher_url:
        return False
    try:
        parsed = urlsplit(publisher_url)
        host = (parsed.hostname or "").lower().rstrip(".")
        if parsed.scheme != "https" or parsed.username or parsed.password:
            return False
        if parsed.port not in (None, 443):
            return False
    except ValueError:
        return False

    if host == "wutheringwaves.kurogames.com":
        return True
    if host in {"x.com", "www.x.com", "twitter.com", "www.twitter.com"}:
        account = parsed.path.strip("/").split("/", 1)[0]
        return account.casefold() == "wuthering_waves"
    return False


def read_bounded_chunks(chunks, max_bytes=MAX_SOURCE_RESPONSE_BYTES):
    data = bytearray()
    for chunk in chunks:
        if not chunk:
            continue
        if len(data) + len(chunk) > max_bytes:
            raise ValueError(f"Source response exceeds {max_bytes} bytes")
        data.extend(chunk)
    return bytes(data)
