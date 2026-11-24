<?php

declare(strict_types=1);

namespace RetroTok\Cloud\Http;

use RetroTok\Cloud\Exception\NotFoundException;

final class Router
{
    /** @var list<array{method: string, pattern: string, paramNames: list<string>, handler: callable}> */
    private array $routes = [];

    public function add(string $method, string $pattern, callable $handler): void
    {
        $paramNames = [];
        $regex = preg_replace_callback('/\{([a-zA-Z_][a-zA-Z0-9_]*)\}/', static function (array $matches) use (&$paramNames): string {
            $paramNames[] = $matches[1];
            return '([^/]+)';
        }, $pattern);

        $this->routes[] = [
            'method' => strtoupper($method),
            'pattern' => '#^' . $regex . '$#',
            'paramNames' => $paramNames,
            'handler' => $handler,
        ];
    }

    public function dispatch(Request $request): Response
    {
        foreach ($this->routes as $route) {
            if ($route['method'] !== $request->method) {
                continue;
            }
            if (preg_match($route['pattern'], $request->path, $matches) !== 1) {
                continue;
            }

            $params = [];
            foreach ($route['paramNames'] as $index => $name) {
                $params[$name] = rawurldecode((string) ($matches[$index + 1] ?? ''));
            }

            return ($route['handler'])($request, $params);
        }

        throw new NotFoundException('Endpoint not found.', 'ENDPOINT_NOT_FOUND');
    }
}
