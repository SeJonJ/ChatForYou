/**
 * electron-builder afterPack 훅: 패키징된 앱의 Electron 퓨즈가 앱이 기대는 값인지 확인한다.
 *
 * 화면은 loadFile(file://) 로 열리는데, 이 상태에서 동작하는 세 가지가 모두
 * GrantFileProtocolExtraPrivileges 퓨즈에 달려 있다.
 * - <script type="module"> 로드 (Vite 빌드 산출물)
 * - 팝업 HTML 을 가져오는 fetch(file://)
 * - 백엔드 API 호출 (file:// 페이지는 Origin 없이 요청해 CORS 허용 목록을 거치지 않는다)
 * 퓨즈가 꺼지면 빌드는 성공해도 앱이 빈 화면이 되므로, 패키징 단계에서 실패시킨다.
 */
const path = require('path');
const { getCurrentFuseWire, FuseV1Options } = require('@electron/fuses');

// 퓨즈 와이어는 켜짐을 ASCII '1' 로 기록한다.
const FUSE_ENABLED = '1'.charCodeAt(0);

/**
 * 플랫폼별로 퓨즈가 기록된 Electron 바이너리 경로를 구한다.
 * @param {import('app-builder-lib').AfterPackContext} context
 * @returns {string}
 */
function resolveElectronBinary(context) {
    const { appOutDir, electronPlatformName, packager } = context;
    const productFilename = packager.appInfo.productFilename;

    if (electronPlatformName === 'darwin' || electronPlatformName === 'mas') {
        return path.join(appOutDir, `${productFilename}.app`);
    }
    if (electronPlatformName === 'win32') {
        return path.join(appOutDir, `${productFilename}.exe`);
    }
    return path.join(appOutDir, packager.executableName);
}

module.exports = async function verifyElectronFuses(context) {
    const binaryPath = resolveElectronBinary(context);
    const fuseWire = await getCurrentFuseWire(binaryPath);
    const state = fuseWire[FuseV1Options.GrantFileProtocolExtraPrivileges];

    if (state !== FUSE_ENABLED) {
        throw new Error(
            `GrantFileProtocolExtraPrivileges 퓨즈가 켜져 있지 않습니다 (${binaryPath}). ` +
            'file:// 로 여는 화면의 모듈 로드·팝업 fetch·API 호출이 모두 차단됩니다.'
        );
    }
    console.log(`  • Electron 퓨즈 확인  GrantFileProtocolExtraPrivileges=Enabled (${context.electronPlatformName})`);
};
