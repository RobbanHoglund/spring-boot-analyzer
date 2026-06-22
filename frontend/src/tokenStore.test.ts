import { describe, expect, it } from 'vitest';

import { maskToken } from './tokenStore';

describe('maskToken', () => {
  it('shows only a fixed mask and the final four characters', () => {
    const masked = maskToken('ghp_exampleTokenValue1234');

    expect(masked).toBe('********1234');
    expect(masked).not.toContain('ghp_');
    expect(masked).not.toContain('exampleTokenValue');
  });

  it('fully masks short tokens', () => {
    expect(maskToken('short')).toBe('********');
  });
});
