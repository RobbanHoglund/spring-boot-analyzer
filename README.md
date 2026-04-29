# spring-boot-analyzer

`spring-boot-analyzer` is a Spring Boot application with a Vite-built frontend that clones remote Git repositories and performs static analysis on Spring Boot source trees.

The app exposes a REST API and a browser UI with separate **Analyze** and **Settings** tabs. The backend clones repositories into a temporary workspace, inspects build files and Java source code, and returns a structured analysis result without running the cloned project.

What it does:

- Clones repositories with JGit
- Supports HTTPS and SSH repository URLs
- Detects Gradle or Maven build files
- Looks for probable Spring Boot usage in build files
- Parses Java source files with JavaParser
- Detects common Spring stereotypes such as `@SpringBootApplication`, `@RestController`, `@Service`, and `@Configuration`
- Reports findings about package structure and component scanning

What it does not do:

- It does not execute code from cloned repositories
- It does not run Gradle, Maven, shell scripts, tests, or application code inside cloned repositories
- It performs static analysis only
- HTTPS tokens selected in the frontend are sent only to the analysis request and are not stored by the backend

Backend:

```bash
./gradlew bootRun
```

Frontend development:

```bash
cd frontend
npm install
npm run dev
```

Open the frontend dev server:

```text
http://localhost:5173/
```

The Vite development server proxies `/api` requests to `http://localhost:8085`.

Build the frontend:

```bash
cd frontend
npm install
npm run build
```

Build the frontend and run the Spring Boot-served UI:

```bash
./gradlew bootRun
```

Open the UI:

```text
http://localhost:8085/
```

Spring Boot serves the built frontend from `frontend/dist`.

Run the tests:

```bash
./gradlew clean test
```

Example request:

```bash
curl -X POST http://localhost:8085/api/analyze \
  -H "Content-Type: application/json" \
  -d '{"repositoryUrl":"https://github.com/example/example-spring-boot-app.git","branch":"main"}'
```

Private HTTPS repository with a token:

```bash
curl -X POST http://localhost:8085/api/analyze \
  -H "Content-Type: application/json" \
  -d '{"repositoryUrl":"https://github.com/example/private-app.git","branch":"main","credentials":{"username":"octocat","token":"***"}}'
```

Frontend settings:

- **Settings > HTTPS token profiles** stores token profiles in browser localStorage.
- **Settings > Saved repositories** stores repository profiles in browser localStorage.
- Saved repositories can reference a default HTTPS token profile.
- SSH repositories use backend/server SSH configuration.
- Browser-stored tokens are sent only during an analyze request and only for HTTPS repository URLs.

Production note:

For now, the frontend and backend are built separately. Later, `frontend/dist` can be served by Spring Boot, nginx, or another static host.
