import { useI18n } from '../i18n';

export function LanguageSelect() {
  const { locale, setLocale } = useI18n();
  return (
    <select
      aria-label="Language"
      className="form-select form-select-sm w-auto"
      value={locale}
      onChange={event => setLocale(event.target.value as 'en' | 'zh-CN')}
    >
      <option value="en">English</option>
      <option value="zh-CN">中文</option>
    </select>
  );
}
