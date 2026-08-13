import { browser } from "$app/environment";

export interface PlaidLinkMetadata {
  institution?: {
    institution_id: string;
    name: string;
  } | null;
}

export interface PlaidLinkHandler {
  open: () => void;
  exit: () => void;
  destroy: () => void;
}

export interface PlaidLinkOptions {
  token: string;
  onSuccess: (publicToken: string, metadata: PlaidLinkMetadata) => void;
  onExit?: (error: { display_message?: string } | null) => void;
}

declare global {
  interface Window {
    Plaid?: {
      create: (options: PlaidLinkOptions) => PlaidLinkHandler;
    };
  }
}

let loader: Promise<NonNullable<Window["Plaid"]>> | null = null;

export function loadPlaidLink(): Promise<NonNullable<Window["Plaid"]>> {
  if (!browser)
    return Promise.reject(new Error("Plaid Link requires a browser"));
  if (window.Plaid) return Promise.resolve(window.Plaid);
  if (loader) return loader;

  loader = new Promise((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(
      "script[data-billguard-plaid-link]",
    );
    const script = existing ?? document.createElement("script");

    const loaded = () => {
      if (window.Plaid) resolve(window.Plaid);
      else reject(new Error("Plaid Link loaded without exposing its client"));
    };
    const failed = () => reject(new Error("Could not load Plaid Link"));

    script.addEventListener("load", loaded, { once: true });
    script.addEventListener("error", failed, { once: true });
    if (!existing) {
      script.src = "https://cdn.plaid.com/link/v2/stable/link-initialize.js";
      script.async = true;
      script.dataset.billguardPlaidLink = "true";
      document.head.appendChild(script);
    }
  });
  return loader;
}
