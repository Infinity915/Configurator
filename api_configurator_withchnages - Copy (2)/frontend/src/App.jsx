import { useState, useEffect } from 'react';
import axios from 'axios';
import { useTheme } from './ThemeContext.jsx';
import './index.css'; 
import './App.css';   

const extractMethod = (id) => {
  if (!id) return 'GET';
  const method = id.split('_')[0].toUpperCase();
  return ['GET', 'POST', 'PUT', 'DELETE', 'PATCH'].includes(method) ? method : 'GET';
};

// --- NEW: Smart Display Name Formatter ---
const getDisplayName = (api) => {
  try {
    // 1. Try to extract the official Swagger summary from the DB template
    if (api.rawPostmanTemplate) {
      const parsed = JSON.parse(api.rawPostmanTemplate);
      // Ensure the name isn't just the raw ID fallback
      if (parsed && parsed.name && !parsed.name.includes("{tenantName}")) {
        return parsed.name;
      }
    }
  } catch (e) {
    // Silently fallback if JSON parsing fails
  }

  // 2. Regex Fallback: If no official name exists, clean up the ugly URL string
  let name = api.id.replace(/^(GET|POST|PUT|PATCH|DELETE)__/, ""); // Strip method
  name = name.replace(/^api_(\{tenantName\})?_?/i, ""); // Strip 'api_{tenantName}_'
  name = name.replace(/_?\{[^}]+\}/g, ""); // Strip variables like '{id}'
  name = name.replace(/[_-]/g, " "); // Replace dashes/underscores with spaces

  // Title Case the words
  return name.split(' ')
    .filter(word => word.length > 0)
    .map(word => word.charAt(0).toUpperCase() + word.slice(1).toLowerCase())
    .join(' ');
};

function App() {
  const { isDark, toggleTheme } = useTheme();
  
  const [formData, setFormData] = useState({ 
    tenantName: '', 
    requestorEmail: '',
    password: '', 
    instanceUrl: '' 
  });
  
  const [availableApis, setAvailableApis] = useState([]);
  const [selectedApiIds, setSelectedApiIds] = useState([]);
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedMethod, setSelectedMethod] = useState('ALL');
  const [hoveredMethod, setHoveredMethod] = useState(null);
  const [isSyncing, setIsSyncing] = useState(false);
  const [isLoading, setIsLoading] = useState(false);

  const [selectedSwaggerUrl, setSelectedSwaggerUrl] = useState("https://cloudstaging3.contractpod.com/cpaimt_api/swagger/v1/swagger.json");
  const isBusinessUrl = selectedSwaggerUrl.includes("business");

  const isFormValid = 
    (isBusinessUrl ? true : formData.tenantName.trim() !== '') && 
    formData.requestorEmail.trim() !== '' &&
    formData.password.trim() !== '';

  useEffect(() => {
    const loadInitialApis = async () => {
      try {
        const response = await axios.get('http://localhost:8080/api/v1/generator/templates');
        setAvailableApis(response.data);
      } catch (error) { console.error("Backend offline:", error); }
    };
    loadInitialApis();
  }, []);

  const handleInputChange = (e) => setFormData({ ...formData, [e.target.name]: e.target.value });

  const handleSync = async () => {
    if (!selectedSwaggerUrl) return;

    setIsSyncing(true);
    try {
      await axios.post(`http://localhost:8080/api/v1/generator/sync?url=${encodeURIComponent(selectedSwaggerUrl)}`);
      const response = await axios.get('http://localhost:8080/api/v1/generator/templates');
      setAvailableApis(response.data);
      alert("Database Synced! Latest schemas loaded.");
    } catch (error) { alert("Sync failed."); } finally { setIsSyncing(false); }
  };

  // ADVANCED AUTO-SELECT LOGIC WITH DEPENDENCIES
  const handleCheckboxChange = (api) => {
    setSelectedApiIds((prev) => {
      const isSelecting = !prev.includes(api.id);
      let newSelection = isSelecting ? [...prev, api.id] : prev.filter(id => id !== api.id);

      // SKIP auto-select logic entirely for Business APIs
      const isBusinessApi = api.apiFamily === 'BUSINESS' || (api.id && api.id.toLowerCase().includes('business'));
      if (isBusinessApi) {
        return newSelection;
      }

      if (isSelecting && /^(POST|PUT|PATCH|DELETE)__/.test(api.id)) {
        const cleanId = api.id.replace(/^(GET|POST|PUT|PATCH|DELETE)__/, "");
        const parts = cleanId.split("_");
        
        if (parts.length >= 3) {
          const coreFamily = parts[2].toLowerCase();
          let familiesToSelect = [coreFamily];

          const dependencyMap = {
            "client": ["client-type", "country", "masters"], 
            "contract-request": ["applicationtype", "department", "legal-party", "country", "client-type", "user"],
            "user": ["roles", "department", "country", "masters"],
            "auto-assignment-rule": ["applicationtype", "user", "department"]
          };

          if (dependencyMap[coreFamily]) {
            familiesToSelect = [...familiesToSelect, ...dependencyMap[coreFamily]];
          }

          availableApis.forEach(availableApi => {
            if (availableApi.id.startsWith("GET__")) {
              const availableCleanId = availableApi.id.replace("GET__", "").toLowerCase();
              const matchesFamily = familiesToSelect.some(family => availableCleanId.includes(family));
              
              if (matchesFamily && !newSelection.includes(availableApi.id)) {
                newSelection.push(availableApi.id);
              }
            }
          });
        }
      }
      return newSelection;
    });
  };

  const downloadFile = (filename, content) => {
    const blob = new Blob([content], { type: 'application/json' });
    const link = document.createElement('a');
    link.href = window.URL.createObjectURL(blob);
    link.download = filename;
    link.click();
    link.remove();
  };

  const handleGenerate = async () => {
    if (!isFormValid) return alert("Please fill out Tenant Name, Email, and Password.");
    if (selectedApiIds.length === 0) return alert("Select an API first.");
    
    setIsLoading(true);

    try {
      const response = await axios.post('http://localhost:8080/api/v1/generator/download', {
        ...formData, 
        selectedApiIds,
        environmentType: isBusinessUrl ? "BUSINESS" : "LEGACY"
      }, { responseType: 'blob' });

      const textData = await response.data.text();
      downloadFile(`Leah_Collection_${formData.tenantName}.json`, textData);
    } catch (error) {
      alert("Error generating collection.");
    } finally {
      setIsLoading(false);
    }
  };

  // --- NEW: Search by both human-readable name and raw ID ---
  const filteredApis = availableApis.filter(api => {
    // 1. Filter by Environment
    const isApiBusiness = api.apiFamily === 'BUSINESS' || (api.id && api.id.toLowerCase().includes('business'));
    
    // In Legacy Mode: Hide Business APIs
    // In Business Mode: Show BOTH Legacy and Business APIs
    if (!isBusinessUrl && isApiBusiness) return false;

    const search = searchTerm.toLowerCase();
    const displayName = getDisplayName(api).toLowerCase();
    const rawId = (api.id || "").toLowerCase();
    const matchesSearch = displayName.includes(search) || rawId.includes(search);
    
    if (selectedMethod === 'ALL') return matchesSearch;
    const method = extractMethod(api.id);
    return matchesSearch && method === selectedMethod;
  });

  let buttonText = "Generate Postman Assets";
  if (!isFormValid) buttonText = " Fill Required Credentials First";
  else if (selectedApiIds.length === 0) buttonText = "👈 Select at least one API";
  else if (isLoading) buttonText = "Processing...";
  else buttonText = `Download Collection (${selectedApiIds.length} APIs)`;

  return (
    <div className="container" style={{ paddingBottom: '120px' }}>
      <div className="hero-section" style={{ marginBottom: '30px', padding: '20px', backgroundColor: isDark ? '#1e293b' : '#f8fafc', borderRadius: '8px', borderLeft: '4px solid #10b981' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <h1 style={{ margin: 0, fontSize: '28px' }}>Leah Integration Builder</h1>
          <div style={{ display: 'flex', gap: '10px', alignItems: 'center' }}>
            <select 
              value={selectedSwaggerUrl} 
              onChange={(e) => setSelectedSwaggerUrl(e.target.value)}
              style={{
                padding: '8px 12px',
                borderRadius: '6px',
                border: '1px solid #cbd5e1',
                backgroundColor: isDark ? '#334155' : '#ffffff',
                color: isDark ? '#f8fafc' : '#0f172a',
                fontSize: '14px',
                outline: 'none',
                minWidth: '250px'
              }}
            >
              <option value="https://cloudstaging3.contractpod.com/cpaimt_api/swagger/v1/swagger.json">Legacy API (Cloudstaging3)</option>
              <option value="https://maxis.api.clm.stg3.leahai.com/business/swagger/v1/swagger.json">Business API (Maxis)</option>
            </select>
            <button className="theme-toggle" onClick={toggleTheme}>{isDark ? '☀️' : '🌙'}</button>
            <button className="btn-sync" onClick={handleSync} disabled={isSyncing}>{isSyncing ? 'Syncing...' : '↻ Sync Schemas'}</button>
          </div>
        </div>
      </div>

      <div className="top-panel">
        <h3 style={{ marginTop: 0, marginBottom: '20px', borderBottom: '1px solid #333', paddingBottom: '10px' }}>Step 1: Environment Configuration</h3>
        
        <div className="form-grid">
          {!isBusinessUrl && (
            <div className="form-group">
              <label>Tenant Name <span style={{color: '#ef4444'}}>*</span></label>
              <input className="form-input" type="text" name="tenantName" value={formData.tenantName} onChange={handleInputChange} placeholder="e.g. execo2" />
            </div>
          )}
          <div className="form-group">
            <label>{isBusinessUrl ? "Maxis Business Email" : "Legacy Requestor Email"} <span style={{color: '#ef4444'}}>*</span></label>
            <input className="form-input" type="email" name="requestorEmail" value={formData.requestorEmail} onChange={handleInputChange} placeholder="admin@domain.com" />
          </div>
          <div className="form-group">
            <label>{isBusinessUrl ? "Maxis API Password" : "Legacy API Password"} <span style={{color: '#ef4444'}}>*</span></label>
            <input className="form-input" type="password" name="password" value={formData.password} onChange={handleInputChange} placeholder="Required for Automated Token" />
          </div>
          <div className="form-group">
            <label>Cloud Instance <span style={{fontSize: '11px', color: '#888', fontWeight: 'normal'}}>(Optional)</span></label>
            <input className="form-input" type="text" name="instanceUrl" value={formData.instanceUrl} onChange={handleInputChange} placeholder="e.g. cloudstaging" />
          </div>
        </div>
      </div>

      <h3 style={{ marginTop: '30px', marginBottom: '15px' }}>Step 2: Select APIs to Generate</h3>
      <input className="swagger-search" type="text" placeholder=" Search APIs by name..." value={searchTerm} onChange={e => setSearchTerm(e.target.value)} />

      <div style={{ display: 'flex', gap: '10px', marginBottom: '20px', flexWrap: 'wrap' }}>
        {['ALL', 'GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map(method => {
          const methodColors = {
            ALL: '#10b981', GET: '#61affe', POST: '#49cc90', 
            PUT: '#fca130', PATCH: '#50e3c2', DELETE: '#f93e3e'
          };
          const isActive = selectedMethod === method;
          const isHovered = hoveredMethod === method;
          const baseColor = methodColors[method];
          const showColor = isActive || isHovered;
          
          return (
            <button
              key={method}
              onClick={() => setSelectedMethod(method)}
              onMouseEnter={() => setHoveredMethod(method)}
              onMouseLeave={() => setHoveredMethod(null)}
              style={{
                padding: '6px 16px',
                borderRadius: '20px',
                border: `1px solid ${showColor ? baseColor : (isDark ? '#475569' : '#cbd5e1')}`,
                backgroundColor: showColor ? `${baseColor}20` : 'transparent',
                color: showColor ? baseColor : (isDark ? '#cbd5e1' : '#475569'),
                cursor: 'pointer',
                fontWeight: '600',
                fontSize: '13px',
                transition: 'all 0.2s ease',
                outline: 'none'
              }}
            >
              {method}
            </button>
          );
        })}
      </div>

      <div className="swagger-group">
          {filteredApis.map(api => {
            const method = extractMethod(api.id);
            return (
              <div key={api.id} className={`swagger-endpoint swagger-${method.toLowerCase()}`}>
                <div className="swagger-summary">
                  <div className="swagger-summary-left">
                    <input type="checkbox" checked={selectedApiIds.includes(api.id)} onChange={() => handleCheckboxChange(api)} style={{transform: 'scale(1.2)', marginRight: '10px'}}/>
                    <span className="swagger-badge">{method}</span>
                    <span style={{
                      marginRight: '10px',
                      fontSize: '11px',
                      fontWeight: 'bold',
                      padding: '3px 8px',
                      borderRadius: '12px',
                      backgroundColor: api.apiFamily === 'BUSINESS' || (api.id && api.id.toLowerCase().includes('business')) ? '#8b5cf6' : '#64748b',
                      color: '#fff'
                    }}>
                      {api.apiFamily === 'BUSINESS' || (api.id && api.id.toLowerCase().includes('business')) ? 'BUSINESS' : 'LEGACY'}
                    </span>
                    {/* NEW: Rendering the beautiful display name instead of the raw ID */}
                    <span className="swagger-path" style={{ fontWeight: '500' }}>{getDisplayName(api)}</span>
                  </div>
                </div>
              </div>
            );
          })}
      </div>

      <div className="action-footer" style={{ borderTop: isFormValid && selectedApiIds.length > 0 ? '3px solid #10b981' : '3px solid #333' }}>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', width: '100%' }}>
          <button 
            className="btn-generate" 
            onClick={handleGenerate} 
            disabled={isLoading || selectedApiIds.length === 0 || !isFormValid}
            style={{ width: '100%', maxWidth: '400px', padding: '15px', fontSize: '16px', backgroundColor: (!isFormValid || selectedApiIds.length === 0) ? '#475569' : '#10b981' }}
          >
            {buttonText}
          </button>
        </div>
      </div>
    </div>
  );
}

export default App;