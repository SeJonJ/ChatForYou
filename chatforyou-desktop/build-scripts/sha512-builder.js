#!/usr/bin/env node

/**
 * 해당 파일은 MAC 플랫폼에서 자동업데이트를 위한 zip 파일 생성 시 사용되는 파일입니다.
 * 특히 SHA512 체크섬 값을 업데이트하는 역할을 합니다.
 * 현재는 주석처리되어 사용되지 않지만, 추후 활성화 예정입니다.
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

class SHA512Builder {
  constructor() {
    this.distDir = path.join(process.cwd(), 'dist');
    this.platform = process.platform;
    this.logPrefix = '[SHA512-FIX]';
  }

  log(message) {
    console.log(`${this.logPrefix} ${message}`);
  }

  error(message) {
    console.error(`${this.logPrefix} ERROR: ${message}`);
  }

  // SHA512 계산 (electron-builder와 동일한 방식)
  async calculateSHA512(filePath) {
    return new Promise((resolve, reject) => {
      const hash = crypto.createHash('sha512');
      const stream = fs.createReadStream(filePath);
      
      stream.on('error', reject);
      stream.on('data', chunk => hash.update(chunk));
      stream.on('end', () => {
        resolve(hash.digest('base64'));
      });
    });
  }

  // 플랫폼별 ZIP 파일 찾기
  findZipFiles() {
    try {
      if (!fs.existsSync(this.distDir)) {
        throw new Error(`dist 디렉토리가 존재하지 않습니다: ${this.distDir}`);
      }

      const files = fs.readdirSync(this.distDir);
      const zipFiles = files.filter(file => {
        const ext = path.extname(file).toLowerCase();
        return ext === '.zip';
      });

      if (zipFiles.length === 0) {
        throw new Error('ZIP 파일을 찾을 수 없습니다.');
      }

      return zipFiles.map(file => path.join(this.distDir, file));
    } catch (error) {
      this.error(`ZIP 파일 검색 실패: ${error.message}`);
      throw error;
    }
  }

  // 플랫폼별 latest.yml 파일 찾기
  findLatestYmlFiles() {
    const possibleNames = [
      'latest.yml',
      'latest-mac.yml',
      'latest-win.yml',
      'latest-linux.yml'
    ];

    const ymlFiles = [];
    
    for (const name of possibleNames) {
      const filePath = path.join(this.distDir, name);
      if (fs.existsSync(filePath)) {
        ymlFiles.push(filePath);
      }
    }

    return ymlFiles;
  }

  // YAML 파일에서 SHA512 값 교체
  updateYmlFile(ymlPath, zipFileName, newSHA512) {
    try {
      let content = fs.readFileSync(ymlPath, 'utf8');
      const originalContent = content;

      // files 섹션에서 해당 ZIP 파일의 sha512 찾아서 교체
      const zipNameOnly = path.basename(zipFileName);
      
      // 특정 ZIP 파일의 SHA512만 교체 (더 정확한 패턴)
      const sha512Pattern = new RegExp(
        `(- url: ${zipNameOnly.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}[\\s\\S]*?sha512:\\s*)([A-Za-z0-9+/=_]+)`, 
        'g'
      );

      let updated = false;
      content = content.replace(sha512Pattern, (match, prefix, oldSHA512) => {
        this.log(`${zipNameOnly} SHA512 교체: ${oldSHA512.substring(0, 20)}... → ${newSHA512.substring(0, 20)}...`);
        updated = true;
        return prefix + newSHA512;
      });
      
      // 최상위 path와 sha512도 해당 파일이면 업데이트
      if (content.includes(`path: ${zipNameOnly}`)) {
        const topSha512Pattern = /^sha512:\s*([A-Za-z0-9+/=_]+)/m;
        content = content.replace(topSha512Pattern, `sha512: ${newSHA512}`);
        this.log(`최상위 SHA512도 업데이트: ${zipNameOnly}`);
      }

      if (!updated) {
        this.error(`${ymlPath}에서 SHA512 값을 찾을 수 없습니다.`);
        return false;
      }

      // ARM64를 기본 path로 설정
      if (ymlPath.includes('latest-mac.yml')) {
        const arm64ZipPattern = /ChatForYou-[\d\.]+-arm64-mac\.zip/;
        const arm64Match = content.match(arm64ZipPattern);
        if (!arm64Match) {
          this.error(`ARM64 zip 파일명을 찾을 수 없습니다. 패턴: ${arm64ZipPattern}`);
          return false;
        }
        const arm64FileName = arm64Match[0];

        // path 필드가 존재하는지 확인
        if (!/^path: .*$/m.test(content)) {
          this.error(`YAML에서 'path' 필드를 찾을 수 없습니다. 구조를 확인하세요.`);
          return false;
        }
        content = content.replace(/^path: .*$/m, `path: ${arm64FileName}`);
        this.log(`기본 path를 ARM64 버전으로 설정: ${arm64FileName}`);

        // ARM64 파일의 SHA512를 찾아서 최상위 sha512에도 설정
        const arm64Sha512Pattern = new RegExp(`- url: ${arm64FileName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}[\\s\\S]*?sha512:\\s*([A-Za-z0-9+/=]+)`);
        const arm64Sha512Match = content.match(arm64Sha512Pattern);
        if (!arm64Sha512Match) {
          this.error(`ARM64 zip에 대한 sha512 값을 찾을 수 없습니다. 패턴: ${arm64Sha512Pattern}`);
          return false;
        }
        const arm64Sha512 = arm64Sha512Match[1];

        // 최상위 sha512 필드가 존재하는지 확인
        if (!/^sha512:\s*([A-Za-z0-9+/=_]+)/m.test(content)) {
          this.error(`YAML에서 최상위 'sha512' 필드를 찾을 수 없습니다.`);
          return false;
        }
        content = content.replace(/^sha512:\s*([A-Za-z0-9+/=_]+)/m, `sha512: ${arm64Sha512}`);
        this.log(`최상위 SHA512도 ARM64 버전으로 설정: ${arm64Sha512.substring(0, 20)}...`);
      }

      // 파일 저장
      fs.writeFileSync(ymlPath, content, 'utf8');
      this.log(`${path.basename(ymlPath)} 파일 업데이트 완료`);
      return true;

    } catch (error) {
      this.error(`YAML 파일 업데이트 실패: ${error.message}`);
      return false;
    }
  }

  // 메인 실행 함수
  async run() {
    try {
      this.log('🚀 SHA512 후처리 시작...');
      this.log(`플랫폼: ${this.platform}`);
      this.log(`작업 디렉토리: ${this.distDir}`);

      // 1. ZIP 파일 찾기
      const zipFiles = this.findZipFiles();
      this.log(`발견된 ZIP 파일: ${zipFiles.length}개`);

      // 2. latest.yml 파일 찾기
      const ymlFiles = this.findLatestYmlFiles();
      this.log(`발견된 YML 파일: ${ymlFiles.length}개`);

      if (ymlFiles.length === 0) {
        throw new Error('latest.yml 파일을 찾을 수 없습니다.');
      }

      // 3. 각 ZIP 파일의 SHA512 계산 및 업데이트
      for (const zipFile of zipFiles) {
        this.log(`\n📦 처리 중: ${path.basename(zipFile)}`);
        
        // SHA512 계산
        const newSHA512 = await this.calculateSHA512(zipFile);
        this.log(`새로운 SHA512: ${newSHA512.substring(0, 32)}...`);

        // 해당하는 yml 파일들 업데이트
        for (const ymlFile of ymlFiles) {
          const updated = this.updateYmlFile(ymlFile, zipFile, newSHA512);
          if (updated) {
            this.log(`✅ ${path.basename(ymlFile)} 업데이트 성공`);
          }
        }
      }

      this.log('\n🎉 SHA512 후처리 완료!');
      
    } catch (error) {
      this.error(`실행 실패: ${error.message}`);
      process.exit(1);
    }
  }
}

// if (require.main === module) {
//   const fixer = new SHA512Builder();
//   fixer.run();
// }
// module.exports = SHA512Builder;