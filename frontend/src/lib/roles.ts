/**
 * 角色判定（中立模块，T-ARCH 第 2 步抽出）。
 *
 * <p>为什么单独放一个"中立"文件：这两个判定**三端都要用**（商城顶栏决定要不要显示后台入口、
 * 后台各页做准入、商家端判断卖家），而它们原本住在 `pages/admin/shared.tsx` 里 ——
 * 那个文件同时包含后台的 UI（工单标签栏 `AdminTabs`、拦截页 `AdminDenied`）。
 * 结果：**商城入口为了引一个 5 行的判定函数，把整个后台 UI 模块拖进了自己的产物**
 * （构建后能在商城的 JS 里搜到"运营概览"等后台字样）。抽到中立模块后，商城只依赖判定本身。
 *
 * <p>判定必须**同源相邻**：各页各写一份就会漂移 —— R74-3 删掉的那个恒为 false 的 `isAdmin`
 * （走了全小写比较，而库内是大写存储）就是漂移的样本。
 */

/** 能否进入平台管理后台：运营或管理员（与后端 `LoginUser#isOperator()` 同口径，忽略大小写） */
export function isOperator(role: string | null | undefined): boolean {
  if (!role) return false;
  const upper = role.toUpperCase();
  return upper === 'OPERATOR' || upper === 'ADMIN';
}

/**
 * 是否**管理员**（比 {@link isOperator} 更窄：只认 ADMIN，不含 OPERATOR）。
 *
 * 用在「运营账号」页里**只有管理员能做**的动作（给他人改角色）。
 */
export function isAdminRole(role: string | null | undefined): boolean {
  if (!role) return false;
  return role.toUpperCase() === 'ADMIN';
}

/**
 * 能否进入商家工作台：卖家本人，或运营 / 管理员（后者用于代运营与排障）。
 *
 * <p>与后端 `LoginUser#canAccessSellerConsole()` 同口径。放在本中立模块的原因同 {@link isOperator}：
 * 商城顶栏要按它决定是否显示「商家端」入口，若从 `pages/seller/shared.tsx` 引，
 * 会把整个商家 UI 模块拖进商城产物（E5 已在后台侧踩过一次）。
 */
export function canAccessSellerConsole(role: string | null | undefined): boolean {
  if (!role) return false;
  const upper = role.toUpperCase();
  return upper === 'SELLER' || upper === 'OPERATOR' || upper === 'ADMIN';
}
