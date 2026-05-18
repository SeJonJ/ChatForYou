---
name: chatforyou-ship
description: 기능/버그 개발 완료 후 chatforyou_v2 브랜치에 버전 태그와 함께 배포. Electron 빌드 GitHub Action 트리거 포함.
type: rigid
---

# Ship: 최신 버전 배포

기능 또는 버그 수정 완료 후 메인 브랜치에 버전을 반영하고 git tag를 push하여 Electron 빌드를 트리거합니다.

---

## Step 1: 현재 브랜치 및 버전 확인

다음 명령어를 실행해 현재 상태를 파악합니다.

```bash
# 현재 브랜치 확인
git branch --show-current

# 버전 확인 (3개 파일)
grep "^version" springboot-backend/build.gradle
grep '"version"' nodejs-frontend/package.json | head -1
grep '"version"' chatforyou-desktop/package.json | head -1

# 최신 태그 확인
git tag --sort=-version:refname | head -3
```

---

## Step 2: 버전 일관성 검증

### 확인 규칙
- `build.gradle`, `nodejs-frontend/package.json`, `chatforyou-desktop/package.json` 3개 파일의 버전을 비교
- **build.gradle vs nodejs-frontend/package.json vs chatforyou-desktop/package.json** 이 다른 경우 → **즉시 중단**, 사용자에게 불일치 알림 후 수동 수정 요청

### 버전이 모두 일치하는 경우만 Step 3으로 진행

---

## Step 3: 배포 버전 결정

최신 태그(예: `v1.0.53`)와 현재 코드 버전(예: `1.0.54`)을 비교합니다.

| 상황 | 처리 |
|------|------|
| 코드 버전 > 최신 태그 | 현재 버전으로 진행 (이미 bump됨) |
| 코드 버전 == 최신 태그 | patch 버전 +1 bump 필요 |

사용자에게 최종 배포 버전을 확인합니다:
> "배포할 버전은 `vX.Y.Z`입니다. 진행할까요? (혹은 다른 버전을 원하시면 알려주세요.)"

---

## Step 4: 버전 파일 업데이트 (bump 또는 불일치 수정 필요 시)

버전 변경이 필요한 경우 아래 3개 파일을 수정합니다.

**`springboot-backend/build.gradle`**
```
version = 'X.Y.Z'   ← 새 버전으로 수정
```

**`nodejs-frontend/package.json`**
```json
"version": "X.Y.Z"  ← 새 버전으로 수정
```

**`chatforyou-desktop/package.json`**
```json
"version": "X.Y.Z"  ← 새 버전으로 수정
```

> `chatforyou-desktop/src` 폴더는 절대 수정하지 않음 (nodejs-frontend 동기화본)

---

## Step 5: 메인 브랜치 머지 및 태그 push

사용자에게 최종 확인을 받은 후 아래 순서로 실행합니다.

```bash
# 현재 브랜치명 저장
CURRENT_BRANCH=$(git branch --show-current)
VERSION=X.Y.Z   # Step 3에서 확정된 버전

# 메인 브랜치로 전환 및 머지
git checkout chatforyou_v2
git merge $CURRENT_BRANCH --no-ff -m "chore: release v$VERSION - merge $CURRENT_BRANCH"

# 버전 파일 변경이 있는 경우 commit
git add springboot-backend/build.gradle nodejs-frontend/package.json chatforyou-desktop/package.json
git diff --cached --quiet || git commit -m "chore: bump version to v$VERSION"

# 태그 생성 및 push
git tag v$VERSION
git push origin chatforyou_v2
git push origin v$VERSION
```

---

## Step 6: GitHub Action 트리거 확인

push 후 약 10초 대기 후 실행 상태를 확인합니다.

```bash
gh run list --workflow=chatforyou_release_by_tag.yml --limit=3
```

출력 결과에서 `v$VERSION` 태그로 트리거된 run의 상태를 확인합니다:
- `queued` / `in_progress` → 정상 트리거됨, URL 사용자에게 안내
- run이 없음 → 태그 push 확인 필요 (`git ls-remote --tags origin v$VERSION`)

사용자에게 결과를 안내합니다:
> "GitHub Action이 트리거되었습니다: [run URL]"
> 또는 "Action이 감지되지 않았습니다. 태그 push 상태를 확인해주세요."

---

## 중단 조건 (자동 진행 불가)

- BE(build.gradle)와 FE(nodejs-frontend) 버전 불일치
- merge conflict 발생
- push 실패 (권한, 네트워크 등)
- 사용자가 진행 거부

위 상황 발생 시 즉시 중단하고 상황을 설명합니다.
