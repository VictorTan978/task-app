# Frontend

Angular 22 app (standalone components, signals, RxJS). For the whole project (features, stack, setup, security,
deployment) see the [main README](../README.md).

## Run

The backend must be running on http://localhost:8080 (see the main README). Then:

```bash
npm install
npm start
```

Open http://localhost:4200. In dev, `/api/...` calls are proxied to the backend by [`proxy.conf.json`](proxy.conf.json).

## Test and build

| What | Command |
|---|---|
| Lint | `npm run lint` |
| Unit tests | `npm test -- --watch=false` |
| Production build (output in `dist/frontend/browser`) | `npm run build` |
| End-to-end tests (full stack running) | `npx playwright test` |
| Dependency vulnerability scan | `npm audit` |

End-to-end tests live in [`e2e/`](e2e/); their settings are in [`playwright.config.ts`](playwright.config.ts).

## API

The backend API is described in [`../contracts/openapi.yaml`](../contracts/openapi.yaml), which is the single source of truth for
request and response shapes.
