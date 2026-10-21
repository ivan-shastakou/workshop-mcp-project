import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import Markdown from 'react-markdown';
import { api } from './api';
import type { ChatMessage, User } from './types';

const SUGGESTIONS = [
  'How do I book a meeting room?',
  'Write an agenda for a 30-minute sprint planning',
  'Tips for running an effective meeting',
];

export default function ChatPanel({ user }: { user: User }) {
  const [open, setOpen] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [historyLoaded, setHistoryLoaded] = useState(false);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  // Restore the conversation kept by chat-service the first time the panel is opened
  useEffect(() => {
    if (!open || historyLoaded) return;
    setHistoryLoaded(true);
    api
      .getChatHistory()
      .then(setMessages)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, [open, historyLoaded]);

  useEffect(() => {
    if (open) inputRef.current?.focus();
  }, [open]);

  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight });
  }, [messages, busy]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: globalThis.KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  useEffect(() => () => abortRef.current?.abort(), []);

  const send = async (text: string) => {
    const message = text.trim();
    if (!message || busy) return;
    setInput('');
    setError(null);
    setBusy(true);
    setMessages((prev) => [...prev, { role: 'user', text: message }, { role: 'assistant', text: '' }]);

    const controller = new AbortController();
    abortRef.current = controller;
    try {
      await api.streamChat(
        message,
        (chunk) =>
          setMessages((prev) => {
            const last = prev[prev.length - 1];
            return [...prev.slice(0, -1), { ...last, text: last.text + chunk }];
          }),
        controller.signal,
      );
    } catch (e) {
      if (!controller.signal.aborted) {
        setError(e instanceof Error ? e.message : String(e));
        // drop the empty assistant placeholder if nothing arrived
        setMessages((prev) => (prev[prev.length - 1]?.text === '' ? prev.slice(0, -1) : prev));
      }
    } finally {
      setBusy(false);
      abortRef.current = null;
    }
  };

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    void send(input);
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      void send(input);
    }
  };

  const clearChat = async () => {
    abortRef.current?.abort();
    try {
      await api.clearChatHistory();
      setMessages([]);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const firstName = user.fullName.split(' ')[0];

  return (
    <>
      {!open && (
        <button type="button" className="chat-toggle" onClick={() => setOpen(true)} aria-label="Open AI assistant">
          <span aria-hidden>✦</span> AI Assistant
        </button>
      )}

      <aside className={`chat-panel${open ? ' open' : ''}`} aria-hidden={!open} aria-label="AI assistant">
        <header className="chat-header">
          <div>
            <strong>AI Assistant</strong>
            <small>Powered by Gemini</small>
          </div>
          <div className="chat-header-actions">
            <button type="button" onClick={clearChat} disabled={messages.length === 0} title="Start a new conversation">
              New chat
            </button>
            <button type="button" className="icon-button" onClick={() => setOpen(false)} aria-label="Close assistant">
              ×
            </button>
          </div>
        </header>

        <div className="chat-messages" ref={listRef}>
          {messages.length === 0 && !busy && (
            <div className="chat-empty">
              <p>Hi {firstName}! Ask me anything about booking rooms or planning meetings.</p>
              {SUGGESTIONS.map((s) => (
                <button key={s} type="button" className="chat-suggestion" onClick={() => void send(s)}>
                  {s}
                </button>
              ))}
            </div>
          )}

          {messages.map((m, i) => (
            <div key={i} className={`chat-message ${m.role}`}>
              {m.role === 'assistant' ? (
                m.text ? <Markdown>{m.text}</Markdown> : <span className="typing">Thinking…</span>
              ) : (
                m.text
              )}
            </div>
          ))}

          {error && <div className="error">{error}</div>}
        </div>

        <form className="chat-input" onSubmit={handleSubmit}>
          <textarea
            ref={inputRef}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Type a message…  (Enter to send, Shift+Enter for a new line)"
            rows={2}
            maxLength={4000}
          />
          <button type="submit" className="primary" disabled={busy || !input.trim()}>
            Send
          </button>
        </form>
      </aside>
    </>
  );
}
