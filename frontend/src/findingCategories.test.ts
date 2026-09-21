import { describe, expect, it } from 'vitest';

// Imported as raw text so the backend enum stays the single source of truth; the
// frontend has no build step that could otherwise see the Java file.
import enumSource from '../../src/main/java/com/robbanhoglund/springbootanalyzer/analyzer/model/FindingCategory.java?raw';

import { FINDING_CATEGORIES, findingCategoryLabel } from './views/resultsView';
import { categoryDisplayName } from './views/settingsView';

/**
 * Guards the frontend category surfaces against the backend FindingCategory enum.
 *
 * Both the findings filter and the rule-management catalog hardcode their own copy of the
 * category list. When a category was added to the enum without updating them, findings in
 * that category became unfilterable and the settings page rendered the raw enum constant.
 * These tests fail on the next such drift instead of letting it reach the UI.
 */

function backendCategories(): string[] {
  const body = enumSource.slice(enumSource.indexOf('{') + 1, enumSource.lastIndexOf('}'));
  return body
    .split(',')
    .map((entry: string) => entry.trim())
    .filter((entry: string) => /^[A-Z][A-Z0-9_]*$/.test(entry));
}

describe('finding category parity with the backend enum', () => {
  const categories = backendCategories();

  it('parses the backend enum', () => {
    expect(categories.length).toBeGreaterThan(10);
    expect(categories).toContain('SECURITY');
    expect(categories).toContain('MIGRATION');
  });

  it('offers every backend category in the findings filter', () => {
    const filterable = FINDING_CATEGORIES.filter((value) => value !== 'ALL');
    expect([...filterable].sort()).toEqual([...categories].sort());
  });

  it('gives every backend category a readable label in the findings view', () => {
    for (const category of categories) {
      const label = findingCategoryLabel(category);
      expect(label, `missing label for ${category}`).not.toBe(category);
      expect(label.trim()).not.toBe('');
    }
  });

  it('gives every backend category a readable label in rule management', () => {
    for (const category of categories) {
      const label = categoryDisplayName(category);
      expect(label, `missing display name for ${category}`).not.toBe(category);
      expect(label.trim()).not.toBe('');
    }
  });
});
