// BrowserRouter/Routes/Route/Link are React Router's equivalent of Vue
// Router or Angular's Router module - same idea (map a URL path to a
// component), different API. `<Routes>` is like Angular's `<router-outlet>`
// or Vue's `<router-view>`: it's replaced by whichever `<Route>` matches
// the current URL.
import { BrowserRouter, Routes, Route, Link } from 'react-router-dom'
import PreferencesPage from './pages/PreferencesPage'
import SendLogPage from './pages/SendLogPage'
import DryRunPage from './pages/DryRunPage'
import TemplatesPage from './pages/TemplatesPage'
import './App.css'

// A React component is just a function that returns JSX (that HTML-looking
// syntax below) - there's no separate template file or decorator the way
// Angular uses a class + template, or Vue uses a single-file component.
// The function's return value *is* the template.
function App() {
  return (
    <BrowserRouter>
      <div className="app">
        <nav className="nav">
          <Link to="/preferences">Preferences</Link>
          <Link to="/send-log">Send Log</Link>
          <Link to="/dry-run">Dry Run</Link>
          <Link to="/templates">Templates</Link>
        </nav>
        <main className="content">
          <Routes>
            <Route path="/" element={<PreferencesPage />} />
            <Route path="/preferences" element={<PreferencesPage />} />
            <Route path="/send-log" element={<SendLogPage />} />
            <Route path="/dry-run" element={<DryRunPage />} />
            <Route path="/templates" element={<TemplatesPage />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  )
}

export default App
