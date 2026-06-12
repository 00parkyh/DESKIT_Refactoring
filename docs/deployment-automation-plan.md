# 배포 자동화 정리

작성일: 2026-03-28

## 목적
- Windows 작업 경로에서 백엔드 JAR 파일과 프론트 정적 빌드 파일을 생성한다.
- 생성된 산출물을 WSL Ubuntu의 지정 경로로 복사한다.
- 백엔드는 Docker 이미지까지 빌드하는 흐름을 자동화 대상으로 포함한다.

## 현재 확인된 상태
- 백엔드 프로젝트 루트: `C:\personal_project\refactoring\deskit`
- 프론트 프로젝트 경로: `C:\personal_project\refactoring\deskit\front`
- WSL 배포판: `Ubuntu`
- WSL `root` 계정 진입 가능 확인 완료
- WSL에서 Windows 경로 `/mnt/c/...` 접근 가능 확인 완료
- 기존 백엔드 산출물 확인 경로: `build/libs/deskit-0.0.1-SNAPSHOT.jar`
- 프론트 빌드 스크립트 확인: `npm run build`
- 프론트 기본 산출물 경로: `front/dist`

## 백엔드 빌드 방식
- Gradle Wrapper 사용
- 후보 명령:
```powershell
.\gradlew.bat build
```

또는 JAR 산출물만 명확히 생성하려면:
```powershell
.\gradlew.bat bootJar
```

## 프론트 빌드 방식
- 현재 `front/package.json` 기준 빌드 명령:
```powershell
npm run build
```

- 예상 산출물 경로:
```text
C:\personal_project\refactoring\deskit\front\dist
```

## 확정된 배포 값
- 프론트 배치 경로: `/var/www/deskit/`
- 백엔드 JAR 배치 경로: `/srv/livecommerce/`
- Docker 이미지 이름: `deskit`
- Docker 태그: `latest`

## 백엔드 이미지 빌드 방식
현재 프로젝트 루트에는 별도 `Dockerfile` 이 없다.

따라서 현재 구조에서는 Spring Boot Gradle 플러그인의 `bootBuildImage` 사용이 가장 적절하다.

사용자 기준의 기존 명령 `docker build -t deskit .` 는 태그를 명시하지 않았으므로 실제 의미는 `deskit:latest` 와 같다.

후보 명령:
```powershell
.\gradlew.bat bootBuildImage --imageName=deskit:latest
```

조건:
- Docker Desktop 또는 Docker Engine 이 실행 중이어야 한다.
- 이미지명과 태그 규칙은 배포 환경에 맞게 확정해야 한다.

## 자동화 목표 흐름
1. 백엔드 JAR 생성
2. 프론트 정적 파일 생성
3. 백엔드 Docker 이미지 빌드
4. 생성된 JAR 파일을 WSL Ubuntu의 지정 경로로 복사
5. 생성된 프론트 `dist` 파일을 WSL Ubuntu의 지정 경로로 복사
6. 필요하면 기존 파일 백업 후 덮어쓰기

## WSL 복사 예시
백엔드 JAR:
```powershell
wsl.exe -d Ubuntu -u root -- cp /mnt/c/personal_project/refactoring/deskit/build/libs/deskit-0.0.1-SNAPSHOT.jar /srv/livecommerce/
```

프론트 빌드 파일:
```powershell
wsl.exe -d Ubuntu -u root -- sh -lc "mkdir -p /var/www/deskit && cp -r /mnt/c/personal_project/refactoring/deskit/front/dist/. /var/www/deskit/"
```

## 자동화 스크립트에 포함할 항목
- 백엔드 빌드 실패 시 즉시 중단
- 프론트 빌드 실패 시 즉시 중단
- 대상 경로가 없으면 생성
- 덮어쓰기 전 백업 여부 선택 가능
- 마지막에 생성 파일과 복사 대상 경로 요약 출력

## 자동화 스크립트
- 스크립트 경로: `scripts/deploy-to-wsl.ps1`
- 기본 동작:
```text
backend bootJar -> frontend build -> backend bootBuildImage -> copy to WSL
```
- 기본 대상:
```text
JAR -> /srv/livecommerce/
dist -> /var/www/deskit/
image -> deskit:latest
```

## 다음 구현 제안
- 필요하면 백업 옵션 추가
- 필요하면 systemd 서비스 재시작 또는 컨테이너 재배포 단계 추가

## 주의사항
- 프론트 `.env.production` 값은 현재 `127.0.0.1` 기준이므로 Ubuntu 배포 환경과 맞지 않으면 별도 조정이 필요하다.
- 백엔드 이미지 빌드는 Docker 데몬 상태에 의존한다.
- 실제 배포 반영 전에는 대상 경로와 덮어쓰기 정책을 먼저 확정해야 한다.

## 운영 기준 재판단
- Ubuntu `/srv/livecommerce/` 에 실제 운영용 `Dockerfile` 과 `docker-compose.yml` 이 존재함을 확인했다.
- `Dockerfile` 은 `deskit-0.0.1-SNAPSHOT.jar` 를 `/app/app.jar` 로 복사해 실행한다.
- `docker-compose.yml` 은 `deskit:latest` 이미지를 사용한다.
- 따라서 현재 운영 기준은 `bootBuildImage` 가 아니라 `JAR 복사 -> /srv/livecommerce 에서 docker build -t deskit:latest .` 이다.

## 수정된 자동화 기준
```text
backend bootJar -> frontend build -> copy JAR to WSL -> docker build in /srv/livecommerce -> copy frontend dist to WSL
```

실행 명령 예시:
```powershell
wsl.exe -d Ubuntu -u root -- sh -lc "cd /srv/livecommerce && docker build -t deskit:latest ."
```
