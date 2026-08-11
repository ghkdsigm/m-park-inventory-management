-- 사용자 추가: 타워/허브 현장 인원 (역할=registrar 등록자). 비번 BCrypt($2a$10).
--  · 안봉룡(byahn3)·박성은(metusv1)·전재민(sm34)은 기존(V13)이라 제외/스킵.
--  · 아이디 중복 시 건너뜀(INSERT IGNORE).
SET NAMES utf8mb4;

INSERT IGNORE INTO profiles (id, username, email, password_hash, display_name, job_title, managed_complex, role, can_stock)
VALUES
  (UUID(), 'tower',      NULL, '$2a$10$J6u9zPyM776WdQ05y.x6beGc6bb4vhfw.4y8teOmIi8/y5ZsHx6Hu', '이남영', '관리소장', '타워', 'registrar', 1),
  (UUID(), 'fscvs',      NULL, '$2a$10$rDUgNAgX6FDoYRPmrytTOeseW/m.o5zg9qu.3Qb/Y8CAXgWf7wdW6', '이무성', '시설과장', '타워', 'registrar', 1),
  (UUID(), 'crash',      NULL, '$2a$10$BwomHnTwYZ9SLoGiVC/ScOp7uYU9VSmivZp7ONkFEyDBWxvm/fICG', '최관열', '시설기사', '타워', 'registrar', 1),
  (UUID(), 'skswlsrb',   NULL, '$2a$10$.ozwulOl8Uud6kZWBzOifOpsKKjgRTxCSzhZ5dsMfQjOF27Lcu/re', '김진규', '시설기사', '타워', 'registrar', 1),
  (UUID(), 'dlwjdwo',    NULL, '$2a$10$pIE666HAjl8LNXl1OHrQUO2vY5TKca3jDaAfQVAW3YvwVb/rTaLVG', '이정재', '시설기사', '타워', 'registrar', 1),
  (UUID(), 'zzanglyt',   NULL, '$2a$10$sDfc.uZpoujrYikFrLYtKeeePLOOV6wQCyhmcbfZvdjqzvkMwvolC', '이용탁', '시설기사', '타워', 'registrar', 1),
  (UUID(), 'mmm7418',    NULL, '$2a$10$b53IfhWPX7212959J57pgub7KKGlX5nDDsX568IpPjmzUpjFMkS.O', '김가을', '시설기사', '타워', 'registrar', 1),
  (UUID(), 'hangyu113',  NULL, '$2a$10$VM7I70uFQVIfml5V6veLn.ss.RzpFtMvJAQYDoXBhAh9YKPuU1LDG', '최한규', '시설기사', '타워', 'registrar', 1),
  (UUID(), 'back5588',   NULL, '$2a$10$KHnBFqMCSaauEMjxOOcPq.OKK9Zpq4K.b7eUWvg1SShJmkP8nDq56', '백철호', '영선대리', '허브', 'registrar', 1),
  (UUID(), 'hji4612',    NULL, '$2a$10$8SmYp.4LYyxFF3JQkc3aSu7EZF/XYIaV/4E2myxQm9NNVBHUx97wC', '허준일', '시설기사', '허브', 'registrar', 1),
  (UUID(), 'hankuncho',  NULL, '$2a$10$DdhdqbCXAgYqzgM7jNQhAuj3bwE.1.VF86mlT3LiU/FajNTk.h9Aq', '조한근', '시설기사', '허브', 'registrar', 1),
  (UUID(), 'man4315',    NULL, '$2a$10$HWcpB4XzqC1BjAWiucFcyert3Hu217N12G6LFxRKLM7MINw.WIRqC', '정희성', '시설기사', '허브', 'registrar', 1),
  (UUID(), 'tmdgus0830', NULL, '$2a$10$eHoLUrrzPwata5dPG0X3OOoUcoTUadWSdN6.s1XKoDieD5yjy8MtK', '안승현', '시설기사', '허브', 'registrar', 1),
  (UUID(), 'fhfidpssk',  NULL, '$2a$10$rLOFcxIJI26ZrDhH3u.Iy.T7C9g1gPyA6AYKDpx0J374pcvPZNPX.', '윤주환', '시설기사', '허브', 'registrar', 1),
  (UUID(), 'snaefboy',   NULL, '$2a$10$0tndtXEBPDfZPEMGH8BJNuPqHPnqfkdJd4Sp.FPUqSdXG50n0ChrC', '박해민', '시설기사', '허브', 'registrar', 1);
