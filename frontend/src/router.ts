import { element } from './dom';

export type AppTab = 'analyze' | 'settings';

export function createTabNavigation(
  currentTab: AppTab,
  onSelect: (tab: AppTab) => void
): HTMLElement {
  const nav = element('nav', { className: 'top-nav', attributes: { 'aria-label': 'Primary' } });

  for (const tab of [
    { id: 'analyze' as const, label: 'Analyze' },
    { id: 'settings' as const, label: 'Settings' }
  ]) {
    const button = element('button', {
      className: currentTab === tab.id ? 'nav-tab active' : 'nav-tab',
      text: tab.label,
      attributes: {
        type: 'button'
      }
    });

    button.addEventListener('click', () => onSelect(tab.id));
    nav.appendChild(button);
  }

  return nav;
}
