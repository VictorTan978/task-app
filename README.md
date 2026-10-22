<div align="center">

<img src="docs/logo.svg" width="96" height="96" alt="Task App logo">

# Task App

### Secure, full-stack task management for individuals and teams

[![Live demo](https://img.shields.io/badge/Live_demo-taskapp--frontend--104u.onrender.com-1f6feb?style=for-the-badge&logo=render&logoColor=white)](https://taskapp-frontend-104u.onrender.com)
[![CI](https://img.shields.io/github/actions/workflow/status/VictorTan978/task-app/ci.yml?branch=main&style=for-the-badge&logo=githubactions&logoColor=white&label=CI)](https://github.com/VictorTan978/task-app/actions/workflows/ci.yml)

![Backend tests](https://img.shields.io/badge/Backend-1583_tests_passing-2ea44f?logo=springboot&logoColor=white)
![Angular tests](https://img.shields.io/badge/Angular-1221_tests_passing-2ea44f?logo=angular&logoColor=white)
![E2E tests](https://img.shields.io/badge/🎭_Playwright-516_e2e_tests-2ea44f)

![Angular](https://img.shields.io/badge/Angular_22-DD0031?logo=angular&logoColor=white)
![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?logo=typescript&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot_4-6DB33F?logo=springboot&logoColor=white)
![Java](https://img.shields.io/badge/Java_21-ED8B00?logo=openjdk&logoColor=white)
![Spring Security](https://img.shields.io/badge/Spring_Security-6DB33F?logo=springsecurity&logoColor=white)
![MySQL](https://img.shields.io/badge/MySQL_8.4-4479A1?logo=mysql&logoColor=white)
![Flyway](https://img.shields.io/badge/Flyway-CC0200?logo=flyway&logoColor=white)
![OpenAPI](https://img.shields.io/badge/OpenAPI-6BA539?logo=openapiinitiative&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-2496ED?logo=docker&logoColor=white)
![Render](https://img.shields.io/badge/Render-46E3B7?logo=render&logoColor=black)

A full-stack task manager with personal and shared task lists, reminders, recurring tasks, an admin area,<br>
and sign-in with email or Google. Built with **Angular**, **Spring Boot** and **MySQL**, with security treated as a feature.

<a href="https://taskapp-frontend-104u.onrender.com"><img src="https://img.shields.io/badge/🚀_Open_live_demo-1f6feb?style=for-the-badge" alt="Open live demo"></a>

<sub>Free hosting: the first load after a quiet period can take up to a minute while the backend wakes up.</sub>

</div>

<!-- TODO: add 1–3 screenshots, e.g. ![Task list](docs/screenshots/tasks.png) -->

---

## 📑 Table of contents

- [Features](#-features)
- [Tech stack](#-tech-stack)
- [Architecture](#-architecture)
- [Project structure](#-project-structure)
- [Getting started](#-getting-started)
- [Testing](#-testing)
- [Security](#-security)
- [Configuration](#-configuration)
- [Deployment](#-deployment)
- [API documentation](#-api-documentation)
- [How this project is built](#-how-this-project-is-built)

---

## ✨ Features

- **Tasks**: create, edit, complete; priority, status and tags; due dates and times
- **Search, sort and pagination** across your tasks
- **Reminders and notifications**, plus **recurring tasks**
- **Team lists**: share a list with other users
- **Undo, trash, bulk actions and drag and drop**
- **Accounts**: register with email (verified by link), password reset, **Sign in with Google**
- **Sessions**: "remember me", a maximum session length, "log out other devices"
- **Account settings**
- **Roles**: `USER`, `ADMIN`, `SUPER_ADMIN`, with an admin page to manage users

Each feature has a written spec with acceptance criteria in [`specs/`](specs/).

## 🧰 Tech stack

| Layer | Technology |
|---|---|
| Frontend | Angular 22 (standalone components, signals, RxJS), TypeScript 6 |
| Backend | Spring Boot 4 on Java 21: Spring Web MVC, Spring Security, Spring Data JPA, OAuth2 client, Mail, Validation |
| Database | MySQL 8.4, schema managed by Flyway migrations |
| API contract | OpenAPI 3 ([`contracts/openapi.yaml`](contracts/openapi.yaml)), Swagger UI via springdoc |
| Testing | JUnit 5 + Testcontainers (MySQL), Angular unit tests, Playwright end-to-end tests |
| Local services | Docker Compose: MySQL, Mailpit (catches emails), mock OAuth2 server (fake Google) |
| CI / CD | GitHub Actions (tests, lint, build), Dependabot, Render (Docker backend + static frontend) |

## 🧱 Architecture

```
Browser ──► Angular app (static site)
               │  relative /api/... calls (same origin, session cookie)
               ▼
           Spring Boot API ──► MySQL (Flyway migrations)
               │
               ├──► SMTP (verification, password reset, reminders)
               └──► Google (OpenID Connect sign-in)
```

In production the static site rewrites `/api/*` to the backend, so the browser sees a single origin.

## 📁 Project structure

```
task-app/
├── frontend/              Angular app (+ Playwright tests in frontend/e2e/)
├── backend/               Spring Boot API (Flyway migrations in src/main/resources/db/migration/)
├── contracts/openapi.yaml API contract: single source of truth for the API
├── specs/                 Feature specs with acceptance criteria and decision logs
├── docker-compose.yml     Local MySQL, Mailpit and fake Google
├── render.yaml            Render deployment blueprint
└── .github/               CI workflow and Dependabot config
```

## 🚀 Getting started

### Prerequisites

- Java 21
- Node.js 24 and npm
- Docker (Docker Desktop on macOS/Windows)

### 1. Start the local services

```bash
docker compose up -d db mail oidc
```

| Service | URL | Purpose |
|---|---|---|
| MySQL | `localhost:3306` | Database (creates the migration user `taskapp` and the app user `taskapp_app`) |
| Mailpit | http://localhost:8025 | Every email the app sends in dev lands here |
| Fake Google | http://localhost:8090 | "Sign in with Google" in dev; accepts any user |

All ports are bound to `127.0.0.1` only.

### 2. Run the backend

```bash
cd backend
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

The API runs on http://localhost:8080. Flyway applies database migrations automatically at startup. The `dev`
profile holds local defaults; without it the app starts with production settings (see [Security](#-security)).

### 3. Run the frontend

```bash
cd frontend
npm install
npm start
```

Open http://localhost:4200 and register an account. The verification email appears in Mailpit.

### Make yourself an admin (optional)

Start the backend with `ADMIN_BOOTSTRAP_EMAIL=<your account email>` to make that account the first super admin.
Details in [`backend/README.md`](backend/README.md).

## 🧪 Testing

| What | Command |
|---|---|
| Backend tests (needs Docker for Testcontainers) | `cd backend && ./mvnw test` |
| Frontend lint | `cd frontend && npm run lint` |
| Frontend unit tests | `cd frontend && npm test -- --watch=false` |
| Frontend production build | `cd frontend && npm run build` |
| End-to-end tests (full stack running) | `cd frontend && npx playwright test` |

CI runs the backend tests and the frontend lint, unit tests and build on every pull request and push to `main`.

## 🔒 Security

Security hardening has its own spec ([`specs/security-hardening.md`](specs/security-hardening.md)). Highlights:

**Authentication and sessions**
- Passwords hashed with BCrypt; email verification and password reset by single-use links
- Sign in with Google over OpenID Connect
- Session cookie is `HttpOnly`, `Secure` (in production) and `SameSite=Lax`
- Sessions have a maximum length; "remember me" tokens are stored hashed and never extended by use
- "Log out other devices" ends every other session

**Protection against common attacks**
- CSRF protection on every state-changing request
- Rate limiting on login, registration, password reset and verification emails, plus a temporary per-email lockout after repeated failures
- Login errors never reveal whether an email is registered
- HTTPS enforced with HSTS; forwarded headers are trusted only from known proxies
- Input validation on every request body

**Authorization**
- Role-based access (`USER`, `ADMIN`, `SUPER_ADMIN`); super admins cannot be changed or removed from the app
- Users only see their own tasks and lists shared with them

**Infrastructure**
- Secure by default: without the `dev` profile the app runs with production settings and refuses to start if
  required secrets (such as `DB_URL`) are missing
- Least-privilege database users: the app user can read and write data only; schema changes use a separate
  migration user
- No secrets in the repository: production values are set as environment variables
- API docs (Swagger UI) are off by default
- Security events (logins, lockouts, rate-limit hits, admin changes) go to a dedicated `security.audit` log,
  encoded against log injection

**Supply chain**
- Dependabot opens weekly dependency update PRs
- `npm audit` for the frontend and OWASP Dependency-Check for the backend
  (`./mvnw org.owasp:dependency-check-maven:12.1.0:check`)

## 🔧 Configuration

Every backend setting and its environment variable is documented in
[`backend/src/main/resources/application.yml`](backend/src/main/resources/application.yml). The main ones for
production:

| Variable | Purpose |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Database connection (app user, data only) |
| `DB_MIGRATION_USERNAME`, `DB_MIGRATION_PASSWORD` | Flyway migration user (schema changes) |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | Outgoing email |
| `APP_BASE_URL` | Public URL of the frontend (used in email links) |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | Sign in with Google (optional; set both or neither) |
| `ADMIN_BOOTSTRAP_EMAIL` | Account to make the first super admin (optional) |

## 🌐 Deployment

The app deploys to [Render](https://render.com) with the [`render.yaml`](render.yaml) blueprint: the backend as a
Docker web service and the frontend as a static site. Step-by-step instructions are in
[`backend/README.md`](backend/README.md).

## 📖 API documentation

The API is described in [`contracts/openapi.yaml`](contracts/openapi.yaml). Open it in
[Swagger Editor](https://editor.swagger.io) or any OpenAPI viewer.

## 🧭 How this project is built

Development is spec-driven: every feature starts as a spec in [`specs/`](specs/) with Given/When/Then acceptance
criteria, then the API contract, then the backend and frontend, then end-to-end verification against the criteria.
Decisions and trade-offs are recorded in each spec's decisions log.
