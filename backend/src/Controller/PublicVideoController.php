<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Controller;

use RetroTok\Cloud\Exception\HttpException;
use RetroTok\Cloud\Http\Request;
use RetroTok\Cloud\Http\Response;
use RetroTok\Cloud\Service\PublicVideoService;

final class PublicVideoController
{
    public function __construct(private readonly PublicVideoService $publicVideos)
    {
    }

    /**
     * GET /v/{token}
     *
     * @param array<string, string> $params
     */
    public function view(Request $request, array $params): Response
    {
        $rawToken = (string) ($params['token'] ?? '');

        try {
            $video = $this->publicVideos->resolvePublicVideo($rawToken, $request->clientIp);
        } catch (HttpException $e) {
            if ($request->wantsJson()) {
                return Response::json($e->statusCode, [
                    'ok' => false,
                    'error' => [
                        'code' => $e->errorCode,
                        'message' => $e->getMessage(),
                    ],
                ], $e->headers);
            }
            return Response::html(
                $e->statusCode,
                $this->renderUnavailableHtml($e->errorCode, $e->getMessage()),
                $e->headers
            );
        }

        if ($request->wantsJson()) {
            return Response::json(200, [
                'ok' => true,
                'data' => $video,
            ]);
        }

        return Response::html(200, $this->renderPlayerHtml($video));
    }

    /**
     * @param array<string, mixed> $video
     */
    private function renderPlayerHtml(array $video): string
    {
        $e = static fn (string $v): string => htmlspecialchars($v, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
        $eventName = $e((string) $video['event_name']);
        $track = $e(trim((string) $video['soundtrack_title'] . ' — ' . (string) $video['soundtrack_artist'], ' —'));
        $expiresAt = $e((string) $video['expires_at']);
        $playbackUrl = $e((string) $video['playback_url']);
        $downloadUrl = $e((string) $video['download_url']);

        return <<<HTML
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="robots" content="noindex, nofollow">
  <title>RetroTok — {$eventName}</title>
  <style>
    :root { color-scheme: dark; --bg: #0b0b12; --card: #161624; --accent: #ff2e63; --cyan: #08d9d6; --text: #f5f7ff; --muted: #a0a6c0; }
    * { box-sizing: border-box; }
    body { margin: 0; font-family: system-ui, -apple-system, sans-serif; background: var(--bg); color: var(--text); min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 16px; }
    .card { width: 100%; max-width: 440px; background: var(--card); border-radius: 20px; padding: 20px; border: 1px solid rgba(255,255,255,0.08); }
    .brand { font-weight: 800; letter-spacing: 0.08em; text-transform: uppercase; color: var(--cyan); font-size: 13px; margin-bottom: 6px; }
    h1 { font-size: 20px; margin: 0 0 6px; }
    .meta { color: var(--muted); font-size: 13px; margin-bottom: 14px; }
    video { width: 100%; border-radius: 14px; background: #000; aspect-ratio: 9 / 16; max-height: 68vh; }
    .btn { display: block; width: 100%; text-align: center; text-decoration: none; margin-top: 14px; padding: 14px 18px; border-radius: 12px; background: var(--accent); color: #fff; font-weight: 700; }
    .notice { margin-top: 12px; font-size: 12px; color: var(--muted); text-align: center; }
  </style>
</head>
<body>
  <main class="card">
    <div class="brand">RetroTok Booth</div>
    <h1>{$eventName}</h1>
    <div class="meta">{$track}</div>
    <video controls playsinline preload="metadata" src="{$playbackUrl}"></video>
    <a class="btn" href="{$downloadUrl}" download>Download Video (MP4)</a>
    <p class="notice">Available until {$expiresAt} UTC. Automatic 30-day retention applies.</p>
  </main>
</body>
</html>
HTML;
    }

    private function renderUnavailableHtml(string $code, string $message): string
    {
        $e = static fn (string $v): string => htmlspecialchars($v, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
        $safeTitle = $code === 'VIDEO_EXPIRED' ? 'Video Link Expired' : 'Video Unavailable';
        $safeMsg = $e($message);

        return <<<HTML
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="robots" content="noindex, nofollow">
  <title>RetroTok — {$safeTitle}</title>
  <style>
    body { margin: 0; font-family: system-ui, -apple-system, sans-serif; background: #0b0b12; color: #f5f7ff; min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 20px; }
    .box { max-width: 400px; background: #161624; border-radius: 18px; padding: 24px; text-align: center; border: 1px solid rgba(255,255,255,0.08); }
    h1 { font-size: 20px; margin: 0 0 10px; }
    p { color: #a0a6c0; font-size: 14px; line-height: 1.5; margin: 0; }
  </style>
</head>
<body>
  <main class="box">
    <h1>{$safeTitle}</h1>
    <p>{$safeMsg}</p>
  </main>
</body>
</html>
HTML;
    }
}
