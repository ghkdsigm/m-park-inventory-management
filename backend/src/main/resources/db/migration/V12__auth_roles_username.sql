-- 인증 개편: (1) 로그인 아이디(username) 도입, 이메일은 선택
--            (2) 역할 admin/user → super/manager/registrar
SET NAMES utf8mb4;

/* ========== 1) username(로그인 아이디) ========== */
ALTER TABLE profiles ADD COLUMN username VARCHAR(50) NULL AFTER id;

-- 기존 계정: 이메일 로컬파트를 아이디로 (admin@dongwha.com → admin)
UPDATE profiles SET username = SUBSTRING_INDEX(email, '@', 1)
  WHERE username IS NULL AND email IS NOT NULL AND email <> '';
-- 이메일이 없는 계정 대비(충돌 방지용 임시 아이디)
UPDATE profiles SET username = CONCAT('user_', LEFT(id, 8))
  WHERE username IS NULL OR username = '';

ALTER TABLE profiles MODIFY COLUMN username VARCHAR(50) NOT NULL;
ALTER TABLE profiles ADD CONSTRAINT uq_profiles_username UNIQUE (username);

/* ========== 2) 역할 재편 ========== */
ALTER TABLE profiles DROP CHECK chk_profiles_role;

UPDATE profiles SET role = 'super'   WHERE role = 'admin';
UPDATE profiles SET role = 'manager' WHERE role = 'user';
-- 지정 계정은 슈퍼관리자로
UPDATE profiles SET role = 'super' WHERE username IN ('admin', 'sh.hwang');
-- 세 역할 모두 입/출고 가능(하위호환 컬럼)
UPDATE profiles SET can_stock = 1;

ALTER TABLE profiles ALTER COLUMN role SET DEFAULT 'registrar';
ALTER TABLE profiles ADD CONSTRAINT chk_profiles_role CHECK (role IN ('super', 'manager', 'registrar'));
