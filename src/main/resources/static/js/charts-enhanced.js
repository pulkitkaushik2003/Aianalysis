(() => {
  const config = window.chartPageConfig || {};
  const APP = window.__chartApp = window.__chartApp || {
    currentPayload: null,
    currentStats: emptyStatsSnapshot(),
    activeRequestId: 0,
    maxSavedCharts: 10,
    pieDefaultLimit: 12,
  };

  const HEADERS = parseInlineArray(config.headers);
  const SAMPLE_ROWS = parseInlineArray(config.rawData);
  const NUMERIC_COLUMNS = parseInlineArray(config.numericHeaders);
  const SCHEMA_PROFILES = parseInlineArray(config.schemaProfiles);
  const SMART_RECOMMENDATIONS = parseInlineArray(config.chartRecommendations);
  const SAVED_CHARTS_KEY = buildSavedChartsKey();
  const LOCAL_THEMES = typeof THEMES !== 'undefined' ? THEMES : {
    blue: ['#38bdf8', '#0ea5e9', '#0284c7', '#0369a1', '#075985'],
    purple: ['#a78bfa', '#8b5cf6', '#7c3aed', '#6d28d9', '#5b21b6'],
    green: ['#34d399', '#10b981', '#059669', '#047857', '#065f46'],
    orange: ['#fb923c', '#f97316', '#ea580c', '#c2410c', '#9a3412'],
    multi: ['#38bdf8', '#a78bfa', '#34d399', '#fb923c', '#f472b6', '#facc15', '#4ade80', '#60a5fa', '#f87171', '#c084fc'],
  };

  function parseInlineArray(value) {
    if (Array.isArray(value)) return value;
    if (typeof value === 'string') {
      try {
        const parsed = JSON.parse(value);
        return Array.isArray(parsed) ? parsed : [];
      } catch (error) {
        console.error('Invalid inline array payload', error);
      }
    }
    return [];
  }

  function emptyStatsSnapshot() {
    return {
      count: 0,
      min: null,
      max: null,
      avg: null,
      sum: null,
      median: null,
      mode: null,
      variance: null,
      stdDev: null,
      q1: null,
      q3: null,
      p90: null,
      growth: null,
      skewness: null,
      kurtosis: null,
      correlation: null,
      forecast: null,
    };
  }

  function setControlDisabled(element, disabled) {
    if (!element) return;
    element.disabled = disabled;
    element.classList.toggle('opacity-60', disabled);
  }

  function syncControlVisibility() {
    const isPie = currentType === 'pie';
    const isScatter = currentType === 'scatter';
    const isCount = document.getElementById('aggregationMode').value === 'count';
    const xWrapper = document.getElementById('xAxis').parentElement;
    const ySelect = document.getElementById('yAxis');

    xWrapper.style.display = isPie ? 'none' : 'block';
    document.getElementById('yAxisWrapper').style.display = isPie ? 'none' : 'block';
    document.getElementById('pieColumnsWrapper').classList.toggle('hidden', !isPie);

    setControlDisabled(document.getElementById('aggregationMode'), isPie || isScatter);
    setControlDisabled(document.getElementById('sortMode'), isPie || isScatter);
    setControlDisabled(document.getElementById('categoryLimit'), isScatter);

    const disableYAxis = isPie || (!isScatter && isCount);
    ySelect.disabled = disableYAxis;
    ySelect.classList.toggle('opacity-60', disableYAxis);
  }

  function hydrateYAxisOptions() {
    const ySelect = document.getElementById('yAxis');
    if (!ySelect) return;

    if (!NUMERIC_COLUMNS.length) {
      ySelect.innerHTML = '<option value="">No numeric column found</option>';
      return;
    }

    ySelect.innerHTML = NUMERIC_COLUMNS
      .map(header => `<option value="${escapeAttrSafe(header)}">${escapeHtmlSafe(header)}</option>`)
      .join('');
  }

  function ensureNumericYAxis() {
    const ySelect = document.getElementById('yAxis');
    if (!ySelect) return null;

    if (currentType !== 'scatter' && document.getElementById('aggregationMode').value === 'count') {
      return null;
    }

    const preferred = NUMERIC_COLUMNS.length ? NUMERIC_COLUMNS[0] : ySelect.value;
    if (!NUMERIC_COLUMNS.includes(ySelect.value) && preferred) {
      ySelect.value = preferred;
    }
    return ySelect.value;
  }

  function ensureScatterAxes() {
    if (NUMERIC_COLUMNS.length < 2) return false;

    const xSelect = document.getElementById('xAxis');
    const ySelect = document.getElementById('yAxis');

    if (!NUMERIC_COLUMNS.includes(xSelect.value)) {
      xSelect.value = NUMERIC_COLUMNS[0];
    }

    if (!NUMERIC_COLUMNS.includes(ySelect.value) || ySelect.value === xSelect.value) {
      ySelect.value = NUMERIC_COLUMNS.find(header => header !== xSelect.value) || NUMERIC_COLUMNS[0];
    }

    return true;
  }

  function resolveCategoryLimit() {
    const value = Number(document.getElementById('categoryLimit').value);
    return Number.isFinite(value) && value > 0 ? value : 25;
  }

  function humanizeChartType(type) {
    return {
      bar: 'Bar',
      line: 'Line',
      pie: 'Pie',
      scatter: 'Scatter',
    }[type] || type;
  }

  function humanizeAggregation(value) {
    return {
      sum: 'Sum',
      avg: 'Average',
      min: 'Minimum',
      max: 'Maximum',
      count: 'Count',
    }[value] || value;
  }

  function humanizeSort(value) {
    return {
      auto: 'Auto',
      'value-desc': 'Value high to low',
      'value-asc': 'Value low to high',
      'label-asc': 'Label A to Z',
      'label-desc': 'Label Z to A',
    }[value] || value;
  }

  function capitalize(value) {
    const text = String(value || '');
    return text ? text.charAt(0).toUpperCase() + text.slice(1) : '';
  }

  function shouldUseHorizontalBar(labels) {
    const longestLabel = labels.reduce((max, label) => Math.max(max, String(label ?? '').length), 0);
    return labels.length > 10 || longestLabel > 14;
  }

  function buildThemeColors(count) {
    const palette = LOCAL_THEMES[currentTheme] || LOCAL_THEMES.blue;
    return Array.from({ length: Math.max(count, 1) }, (_, index) => palette[index % palette.length]);
  }

  function truncateLabel(value, maxLength) {
    const text = String(value ?? '');
    return text.length > maxLength ? `${text.slice(0, maxLength - 1)}...` : text;
  }

  function toNumber(value) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }

  function fmt(value) {
    if (value == null || !Number.isFinite(value)) return '--';
    return new Intl.NumberFormat('en-IN', { maximumFractionDigits: 2 })
      .format(Math.round(value * 100) / 100);
  }

  function fmtInteger(value) {
    if (value == null || !Number.isFinite(Number(value))) return '--';
    return new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 }).format(Number(value));
  }

  function formatCompactNumber(value) {
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) return value;
    return new Intl.NumberFormat('en-IN', {
      notation: 'compact',
      maximumFractionDigits: 1,
    }).format(numeric);
  }

  function calcMode(nums) {
    const freq = new Map();
    nums.forEach(num => {
      const key = Math.round(num * 100) / 100;
      freq.set(key, (freq.get(key) || 0) + 1);
    });

    let mode = nums[0];
    let maxCount = 0;

    freq.forEach((count, key) => {
      if (count > maxCount) {
        maxCount = count;
        mode = key;
      }
    });

    return maxCount <= 1 ? null : Number(mode);
  }

  function calcVariance(nums) {
    if (nums.length < 2) return 0;
    const avg = nums.reduce((a, b) => a + b, 0) / nums.length;
    return nums.reduce((sum, num) => sum + Math.pow(num - avg, 2), 0) / (nums.length - 1);
  }

  function calcStdDev(nums) {
    return Math.sqrt(calcVariance(nums));
  }

  function calcPercentile(nums, percentile) {
    const sorted = [...nums].sort((a, b) => a - b);
    const pos = (percentile / 100) * (sorted.length - 1);
    const lower = Math.floor(pos);
    const upper = Math.ceil(pos);
    if (lower === upper) return sorted[lower];
    const weight = pos - lower;
    return sorted[lower] * (1 - weight) + sorted[upper] * weight;
  }

  function calcGrowth(nums) {
    if (nums.length < 2 || nums[0] === 0) return 0;
    return ((nums[nums.length - 1] - nums[0]) / nums[0]) * 100;
  }

  function calcSkewness(nums) {
    if (nums.length < 3) return 0;
    const avg = nums.reduce((a, b) => a + b, 0) / nums.length;
    const std = calcStdDev(nums);
    if (std === 0) return 0;
    const n = nums.length;
    const thirdMoment = nums.reduce((sum, num) => sum + Math.pow((num - avg) / std, 3), 0);
    return (n / ((n - 1) * (n - 2))) * thirdMoment;
  }

  function calcKurtosis(nums) {
    if (nums.length < 4) return 0;
    const avg = nums.reduce((a, b) => a + b, 0) / nums.length;
    const std = calcStdDev(nums);
    if (std === 0) return 0;
    const n = nums.length;
    const fourthMoment = nums.reduce((sum, num) => sum + Math.pow((num - avg) / std, 4), 0);
    const numerator = (n * (n + 1) * fourthMoment) - (3 * Math.pow(n - 1, 3));
    const denominator = (n - 1) * (n - 2) * (n - 3);
    return denominator === 0 ? 0 : numerator / denominator;
  }

  function calcCorrelation(x, y) {
    if (x.length < 2 || x.length !== y.length) return 0;
    const avgX = x.reduce((a, b) => a + b, 0) / x.length;
    const avgY = y.reduce((a, b) => a + b, 0) / y.length;
    let num = 0;
    let sumSqX = 0;
    let sumSqY = 0;

    for (let index = 0; index < x.length; index++) {
      const dx = x[index] - avgX;
      const dy = y[index] - avgY;
      num += dx * dy;
      sumSqX += dx * dx;
      sumSqY += dy * dy;
    }

    const den = Math.sqrt(sumSqX * sumSqY);
    return den === 0 ? 0 : num / den;
  }

  function calcForecast(nums) {
    if (nums.length < 3) return nums[nums.length - 1] || 0;
    const recent = nums.slice(-3);
    return recent.reduce((a, b) => a + b, 0) / recent.length;
  }

  function escapeHtmlSafe(text) {
    return String(text)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function escapeAttrSafe(text) {
    return String(text)
      .replace(/&/g, '&amp;')
      .replace(/"/g, '&quot;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
  }

  function escapeJsSafe(text) {
    return String(text).replace(/\\/g, '\\\\').replace(/'/g, "\\'");
  }

  function sanitizeFileName(value) {
    return String(value || 'chart')
      .trim()
      .replace(/[<>:"/\\|?*\x00-\x1F]/g, '-')
      .replace(/\s+/g, '-')
      .slice(0, 80) || 'chart';
  }

  function setMessage(message, kind) {
    const element = document.getElementById('chartMessage');
    if (!element) return;

    if (!message) {
      element.textContent = '';
      element.className = 'hidden text-xs rounded-lg px-3 py-2 mb-3';
      return;
    }

    const styles = {
      info: 'bg-slate-800 text-slate-200 border border-slate-700',
      success: 'bg-emerald-500/10 text-emerald-300 border border-emerald-500/30',
      error: 'bg-rose-500/10 text-rose-300 border border-rose-500/30',
    };

    element.textContent = message;
    element.className = `text-xs rounded-lg px-3 py-2 mb-3 ${styles[kind] || styles.info}`;
  }

  function setChartMeta(text) {
    const element = document.getElementById('chartMeta');
    if (element) {
      element.textContent = text || 'Selected axis ke hisaab se grouped chart show hoga.';
    }
  }

  function setLoading(isLoading) {
    const saveButton = document.getElementById('saveChartBtn');
    if (!saveButton) return;
    saveButton.disabled = isLoading;
    saveButton.classList.toggle('opacity-60', isLoading);
    saveButton.classList.toggle('cursor-not-allowed', isLoading);
  }

  function activateChartType(type) {
    currentType = type;
    ['bar', 'line', 'pie', 'scatter'].forEach(item => {
      const button = document.getElementById('btn-' + item);
      if (button) {
        button.classList.toggle('active', item === type);
      }
    });
    syncControlVisibility();
  }

  function renderSchemaHighlights() {
    const container = document.getElementById('schemaHighlights');
    const summary = document.getElementById('recommendationSummary');
    if (!container || !summary) return;

    const measures = SCHEMA_PROFILES.filter(profile => profile.role === 'measure');
    const temporal = SCHEMA_PROFILES.filter(profile => profile.role === 'temporal');
    const dimensions = SCHEMA_PROFILES.filter(profile => profile.role === 'dimension');
    const identifiers = SCHEMA_PROFILES.filter(profile => profile.role === 'identifier');

    summary.textContent = SCHEMA_PROFILES.length
      ? `Detected ${measures.length} measure, ${temporal.length} time field, ${dimensions.length} dimension, ${identifiers.length} identifier columns.`
      : 'Dataset schema analyze karke smart chart suggestions yahan dikhenge.';

    const highlights = [...measures, ...temporal, ...dimensions].slice(0, 8);
    container.innerHTML = highlights.length
      ? highlights.map(profile => `
          <span class="inline-flex items-center gap-2 rounded-full border px-3 py-1 text-[11px] ${
            schemaBadgeClass(profile.role)
          }">
            <span>${schemaRoleLabel(profile.role)}</span>
            <span class="text-white/90">${escapeHtmlSafe(profile.name)}</span>
          </span>
        `).join('')
      : '<span class="text-xs text-gray-500">Schema highlights upload ke baad show honge.</span>';
  }

  function schemaRoleLabel(role) {
    return {
      measure: 'Measure',
      temporal: 'Time',
      dimension: 'Dimension',
      identifier: 'ID',
      text: 'Text',
    }[role] || 'Field';
  }

  function schemaBadgeClass(role) {
    return {
      measure: 'border-emerald-500/30 bg-emerald-500/10 text-emerald-300',
      temporal: 'border-sky-500/30 bg-sky-500/10 text-sky-300',
      dimension: 'border-amber-500/30 bg-amber-500/10 text-amber-300',
      identifier: 'border-slate-700 bg-slate-800 text-slate-300',
      text: 'border-violet-500/30 bg-violet-500/10 text-violet-300',
    }[role] || 'border-slate-700 bg-slate-800 text-slate-300';
  }

  function recommendationButtonClass(isActive) {
    return isActive
      ? 'border-sky-400 bg-sky-500/10 text-sky-200'
      : 'border-slate-700 bg-slate-800 hover:bg-slate-700 text-gray-300';
  }

  function currentRecommendationKey() {
    if (currentType === 'pie') {
      return `pie|null|null|count|${selectedPieColumns.join(',')}`;
    }

    const aggregation = document.getElementById('aggregationMode')?.value || 'sum';
    const xAxis = document.getElementById('xAxis')?.value || 'null';
    const yAxis = currentType === 'scatter'
      ? (document.getElementById('yAxis')?.value || 'null')
      : aggregation === 'count'
        ? 'null'
        : (document.getElementById('yAxis')?.value || 'null');
    return `${currentType}|${xAxis}|${yAxis}|${aggregation}|`;
  }

  function renderSmartSuggestions() {
    const container = document.getElementById('suggestedCharts');
    if (!container) return;

    const items = SMART_RECOMMENDATIONS.length
      ? SMART_RECOMMENDATIONS
      : [{
        id: 'fallback-bar',
        chartType: 'bar',
        badge: 'Compare',
        icon: '📊',
        title: 'Bar Chart',
        description: 'Category vs measure comparison.',
      }];
    const activeKey = currentRecommendationKey();

    container.innerHTML = items.map(item => {
      const pieKey = Array.isArray(item.pieColumns) ? item.pieColumns.join(',') : '';
      const itemKey = item.id || `${item.chartType}|${item.xAxis}|${item.yAxis}|${item.aggregation}|${pieKey}`;
      return `
        <button onclick="applyRecommendation('${escapeJsSafe(itemKey)}')"
                class="w-full text-left rounded-2xl border px-4 py-3 transition ${recommendationButtonClass(itemKey === activeKey)}">
          <div class="flex items-start justify-between gap-3">
            <div class="min-w-0">
              <p class="text-sm font-semibold text-white flex items-center gap-2">
                <span>${item.icon || '📊'}</span>
                <span>${escapeHtmlSafe(item.title || 'Suggested chart')}</span>
              </p>
              <p class="text-xs text-gray-400 mt-1">${escapeHtmlSafe(item.description || '')}</p>
            </div>
            <span class="text-[11px] rounded-full border border-slate-600 px-2 py-1 text-gray-300">
              ${escapeHtmlSafe(item.badge || humanizeChartType(item.chartType || 'chart'))}
            </span>
          </div>
        </button>
      `;
    }).join('');
  }

  function describeDataScope() {
    if (config.chartScope === 'server-aggregated') {
      return `Large dataset mode: full ${fmtInteger(config.totalRows)} rows scanned on server`;
    }
    if (config.chartScope === 'preview') {
      return `Preview mode: ${fmtInteger(config.chartRows)} sampled rows used from ${fmtInteger(config.totalRows || config.chartRows)}`;
    }
    if (config.chartScope === 'full') {
      return `Loaded rows: ${fmtInteger(config.totalRows || config.chartRows)}`;
    }
    return config.largeFileMode
      ? `Large dataset mode active for ${fmtInteger(config.totalRows)} rows`
      : `Preview rows available: ${fmtInteger(config.chartRows || SAMPLE_ROWS.length)}`;
  }

  function buildChartMeta(payload) {
    return [
      payload.note,
      payload.sourceRows != null ? `Rows scanned: ${fmtInteger(payload.sourceRows)}` : null,
      payload.groupCount != null
        ? `Groups visible: ${fmtInteger(payload.displayedCount || 0)} / ${fmtInteger(payload.groupCount)}`
        : null,
      payload.sort ? `Sort: ${humanizeSort(payload.sort)}` : null,
      describeDataScope(),
    ].filter(Boolean).join(' | ');
  }

  function buildPieMeta(payload, seriesList) {
    const summary = seriesList.map(series =>
      `${series.column}: ${fmtInteger(series.displayedCount || 0)} / ${fmtInteger(series.groupCount || 0)} slices`
    ).join(' | ');

    return [
      payload.note,
      payload.sourceRows != null ? `Rows scanned: ${fmtInteger(payload.sourceRows)}` : null,
      payload.sliceLimit ? `Max slices per pie: ${fmtInteger(payload.sliceLimit)}` : null,
      `Mode: ${capitalize(payload.mode || currentPieMode)}`,
      summary,
      describeDataScope(),
    ].filter(Boolean).join(' | ');
  }

  function showSingleChartArea(height, minWidth = null) {
    currentPieCharts.forEach(chart => chart.destroy());
    currentPieCharts = [];
    document.getElementById('singleChartWrap').classList.remove('hidden');
    document.getElementById('pieChartsGrid').classList.add('hidden');
    document.getElementById('pieEmptyState').classList.add('hidden');
    document.getElementById('singleChartWrap').style.height = `${height}px`;
    const sizer = document.getElementById('chartCanvasSizer');
    if (sizer) {
      if (minWidth) {
        sizer.style.minWidth = `${minWidth}px`;
        sizer.style.width = `${minWidth}px`;
      } else {
        sizer.style.minWidth = '0px';
        sizer.style.width = '100%';
      }
    }
  }

  function showPieArea() {
    if (currentChart) {
      currentChart.destroy();
      currentChart = null;
    }
    document.getElementById('singleChartWrap').classList.add('hidden');
    document.getElementById('pieChartsGrid').classList.remove('hidden');
  }

  async function fetchJson(url) {
    const response = await fetch(url, {
      headers: {
        Accept: 'application/json',
      },
    });

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }

    return response.json();
  }

  function renderCurrentChart(payload) {
    if (payload.chartType === 'scatter') {
      renderScatterChart(payload);
      document.getElementById('chartTitle').textContent =
        `Scatter Chart - ${payload.xAxis} vs ${payload.yAxis}`;
      window.updateStats(payload.values || [], payload.xValues || []);
      setChartMeta(buildChartMeta(payload));
      return;
    }

    renderCategoryChart(payload);
    const metricLabel = payload.aggregation === 'count'
      ? 'Count'
      : `${humanizeAggregation(payload.aggregation)} ${payload.yAxis}`;
    document.getElementById('chartTitle').textContent =
      `${humanizeChartType(payload.chartType)} Chart - ${payload.xAxis} vs ${metricLabel}`;
    window.updateStats(payload.values || [], payload.xValues || []);
    setChartMeta(buildChartMeta(payload));
  }

  function renderCategoryChart(payload) {
    const labels = Array.isArray(payload.labels) ? payload.labels : [];
    const values = Array.isArray(payload.values) ? payload.values : [];
    const colors = buildThemeColors(labels.length);
    const pointRadius = labels.length > 60 ? 2 : 4;
    const shouldExpandWidth = payload.chartType === 'bar' && shouldUseHorizontalBar(labels);
    const minWidth = shouldExpandWidth ? Math.max(960, labels.length * 72) : null;

    showSingleChartArea(420, minWidth);

    const ctx = document.getElementById('myChart').getContext('2d');
    if (currentChart) {
      currentChart.destroy();
    }

    currentChart = new Chart(ctx, {
      type: payload.chartType,
      data: {
        labels,
        datasets: [{
          label: payload.aggregation === 'count'
            ? 'Count'
            : `${humanizeAggregation(payload.aggregation)} ${payload.yAxis}`,
          data: values,
          backgroundColor: payload.chartType === 'line'
            ? colors[0] + '22'
            : colors.map(color => color + 'cc'),
          borderColor: payload.chartType === 'line' ? colors[0] : colors.map(color => color),
          borderWidth: payload.chartType === 'line' ? 2.5 : 1,
          fill: payload.chartType === 'line',
          tension: 0.28,
          pointRadius,
          pointHoverRadius: pointRadius + 1,
          pointBackgroundColor: colors[0],
          borderRadius: payload.chartType === 'bar' ? 8 : 0,
          maxBarThickness: 56,
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        indexAxis: 'x',
        plugins: {
          legend: {
            display: false,
          },
          tooltip: {
            backgroundColor: '#0f172a',
            titleColor: '#e2e8f0',
            bodyColor: '#cbd5e1',
            borderColor: '#334155',
            borderWidth: 1,
            callbacks: {
              label(context) {
                return `${context.dataset.label}: ${fmt(context.parsed.y ?? context.parsed.x)}`;
              },
            },
          },
        },
        scales: {
          x: {
            grid: { color: '#1e293b' },
            ticks: {
              color: '#94a3b8',
              autoSkip: false,
              maxRotation: labels.length > 12 ? 55 : 0,
              minRotation: labels.length > 12 ? 30 : 0,
              callback(value, index) {
                return truncateLabel(labels[index] ?? value, labels.length > 18 ? 14 : 22);
              },
            },
          },
          y: {
            beginAtZero: true,
            grid: { color: '#1e293b' },
            ticks: {
              color: '#94a3b8',
              callback(value, index) {
                return formatCompactNumber(value);
              },
            },
          },
        },
      },
    });
  }

  function renderScatterChart(payload) {
    const points = Array.isArray(payload.points) ? payload.points : [];
    const colors = LOCAL_THEMES[currentTheme] || LOCAL_THEMES.blue;
    const pointRadius = points.length > 700 ? 2 : points.length > 350 ? 3 : 4;

    showSingleChartArea(440);

    const ctx = document.getElementById('myChart').getContext('2d');
    if (currentChart) {
      currentChart.destroy();
    }

    currentChart = new Chart(ctx, {
      type: 'scatter',
      data: {
        datasets: [{
          label: `${payload.xAxis} vs ${payload.yAxis}`,
          data: points,
          backgroundColor: colors[0] + 'bb',
          borderColor: colors[0],
          pointRadius,
          pointHoverRadius: pointRadius + 2,
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: {
            display: false,
          },
          tooltip: {
            backgroundColor: '#0f172a',
            titleColor: '#e2e8f0',
            bodyColor: '#cbd5e1',
            borderColor: '#334155',
            borderWidth: 1,
          },
        },
        scales: {
          x: {
            title: {
              display: true,
              text: payload.xAxis,
              color: '#94a3b8',
            },
            grid: { color: '#1e293b' },
            ticks: {
              color: '#94a3b8',
              callback(value) {
                return formatCompactNumber(value);
              },
            },
          },
          y: {
            title: {
              display: true,
              text: payload.yAxis,
              color: '#94a3b8',
            },
            grid: { color: '#1e293b' },
            ticks: {
              color: '#94a3b8',
              callback(value) {
                return formatCompactNumber(value);
              },
            },
          },
        },
      },
    });
  }

  function renderPiePayload(payload) {
    const grid = document.getElementById('pieChartsGrid');
    const empty = document.getElementById('pieEmptyState');
    const colors = LOCAL_THEMES[currentTheme] || LOCAL_THEMES.blue;
    const seriesList = payload.mode === 'combined' && payload.combined
      ? [payload.combined]
      : (Array.isArray(payload.series) ? payload.series : []);

    showPieArea();
    empty.classList.add('hidden');
    grid.innerHTML = '';
    currentPieCharts.forEach(chart => chart.destroy());
    currentPieCharts = [];

    seriesList.forEach((series, index) => {
      const labels = Array.isArray(series.labels) ? series.labels : [];
      const values = Array.isArray(series.values) ? series.values : [];
      const percentages = Array.isArray(series.percentages)
        ? series.percentages
        : values.map(value => {
          const total = values.reduce((sum, item) => sum + Number(item || 0), 0);
          return total === 0 ? 0 : (Number(value || 0) * 100) / total;
        });
      const showLegend = labels.length <= 18;
      const breakdown = labels.map((label, itemIndex) => ({
        label,
        value: Number(values[itemIndex] || 0),
        percent: Number(percentages[itemIndex] || 0),
      }));
      const card = document.createElement('div');
      card.className = payload.mode === 'combined'
        ? 'bg-slate-950 border border-slate-800 rounded-2xl p-4 xl:col-span-2'
        : 'bg-slate-950 border border-slate-800 rounded-2xl p-4';
      card.innerHTML = `
        <div class="flex items-start justify-between gap-3 mb-3">
          <div>
            <p class="text-sm font-semibold text-white truncate">${escapeHtmlSafe(series.column || `Pie ${index + 1}`)}</p>
            <p class="text-[11px] text-gray-500 mt-1">
              ${fmtInteger(series.displayedCount || labels.length)} / ${fmtInteger(series.groupCount || labels.length)} categories
              ${series.collapsedCount ? ` • ${fmtInteger(series.collapsedCount)} merged into Others` : ''}
            </p>
          </div>
          <span class="text-[11px] text-gray-400">${showLegend ? 'Legend visible' : 'Breakdown shown below'}</span>
        </div>
        <div class="grid grid-cols-1 ${payload.mode === 'combined' ? 'xl:grid-cols-[minmax(0,1.15fr)_minmax(260px,0.85fr)]' : '2xl:grid-cols-[minmax(0,1.1fr)_minmax(220px,0.9fr)]'} gap-4 items-start">
          <div class="relative" style="height: ${payload.mode === 'combined' ? '420px' : '320px'};">
            <canvas id="pieChart-${index}"></canvas>
          </div>
          <div class="space-y-3">
            <div class="grid grid-cols-2 gap-2 text-xs">
              <div class="rounded-xl border border-slate-800 bg-slate-900/70 px-3 py-2">
                <p class="text-[11px] text-gray-500">Top Share</p>
                <p class="text-sm text-white mt-1">${escapeHtmlSafe(series.dominantLabel || '--')}</p>
                <p class="text-[11px] text-sky-300 mt-1">${fmt(series.dominantShare)}%</p>
              </div>
              <div class="rounded-xl border border-slate-800 bg-slate-900/70 px-3 py-2">
                <p class="text-[11px] text-gray-500">Total</p>
                <p class="text-sm text-white mt-1">${fmtInteger(series.totalValue)}</p>
                <p class="text-[11px] text-gray-400 mt-1">share of whole</p>
              </div>
            </div>
            <div class="rounded-2xl border border-slate-800 bg-slate-900/60 overflow-hidden">
              <div class="px-3 py-2 border-b border-slate-800 text-[11px] uppercase tracking-wide text-gray-500">
                Slice Breakdown
              </div>
              <div class="max-h-60 overflow-y-auto divide-y divide-slate-800/80">
                ${breakdown.map((item, itemIndex) => `
                  <div class="px-3 py-2 flex items-center justify-between gap-3">
                    <div class="min-w-0">
                      <div class="flex items-center gap-2">
                        <span class="inline-block w-2.5 h-2.5 rounded-full" style="background:${colors[itemIndex % colors.length]}"></span>
                        <span class="text-sm text-white truncate">${escapeHtmlSafe(item.label)}</span>
                      </div>
                      <p class="text-[11px] text-gray-500 mt-1">${fmtInteger(item.value)} occurrences</p>
                    </div>
                    <span class="text-xs font-semibold text-sky-300">${fmt(item.percent)}%</span>
                  </div>
                `).join('')}
              </div>
            </div>
          </div>
        </div>
      `;
      grid.appendChild(card);

      const ctx = card.querySelector('canvas').getContext('2d');
      const chart = new Chart(ctx, {
        type: 'doughnut',
        data: {
          labels,
          datasets: [{
            data: values,
            backgroundColor: labels.map((_, itemIndex) => colors[itemIndex % colors.length]),
            borderColor: '#020617',
            borderWidth: 2,
            hoverOffset: 10,
          }],
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          cutout: '56%',
          plugins: {
            legend: {
              display: showLegend,
              position: 'bottom',
              labels: {
                color: '#94a3b8',
                font: { size: 11 },
                boxWidth: 12,
              },
            },
            tooltip: {
              backgroundColor: '#0f172a',
              titleColor: '#e2e8f0',
              bodyColor: '#cbd5e1',
              borderColor: '#334155',
              borderWidth: 1,
              callbacks: {
                label(context) {
                  const value = Number(context.parsed || 0);
                  const percent = Number(percentages[context.dataIndex] || 0);
                  return `${context.label}: ${fmtInteger(value)} (${fmt(percent)}%)`;
                },
              },
            },
          },
        },
      });

      currentPieCharts.push(chart);
    });

    document.getElementById('chartTitle').textContent =
      `Pie Chart - ${payload.columns.join(', ')} (${capitalize(currentPieMode)})`;
    window.updateStats(seriesList.flatMap(series => Array.isArray(series.values) ? series.values : []));
    setChartMeta(buildPieMeta(payload, seriesList));
  }

  function captureCurrentChartImage(format = 'image/png') {
    if (currentType === 'pie') {
      const canvases = Array.from(document.querySelectorAll('#pieChartsGrid canvas'));
      return combineCanvases(canvases, format);
    }
    const canvas = document.getElementById('myChart');
    return canvas ? exportCanvas(canvas, format) : null;
  }

  function exportCanvas(canvas, format = 'image/png') {
    if (!canvas || !canvas.width || !canvas.height) return null;

    const maxWidth = format === 'image/png' ? 1600 : 1100;
    const scale = Math.min(1, maxWidth / canvas.width);
    const output = document.createElement('canvas');
    output.width = Math.max(1, Math.floor(canvas.width * scale));
    output.height = Math.max(1, Math.floor(canvas.height * scale));
    const ctx = output.getContext('2d');

    ctx.fillStyle = '#020617';
    ctx.fillRect(0, 0, output.width, output.height);
    ctx.drawImage(canvas, 0, 0, output.width, output.height);

    return output.toDataURL(format, format === 'image/png' ? undefined : 0.9);
  }

  function combineCanvases(canvases, format = 'image/png') {
    const visibleCanvases = canvases.filter(canvas => canvas && canvas.width && canvas.height);
    if (!visibleCanvases.length) return null;

    const columns = visibleCanvases.length > 1 ? 2 : 1;
    const gap = 24;
    const titleHeight = 34;
    const tileWidth = 520;
    const tileHeight = 320;
    const rows = Math.ceil(visibleCanvases.length / columns);
    const output = document.createElement('canvas');

    output.width = columns * tileWidth + (columns + 1) * gap;
    output.height = rows * (tileHeight + titleHeight) + (rows + 1) * gap;

    const ctx = output.getContext('2d');
    ctx.fillStyle = '#020617';
    ctx.fillRect(0, 0, output.width, output.height);
    ctx.font = '600 18px sans-serif';
    ctx.textBaseline = 'top';

    visibleCanvases.forEach((canvas, index) => {
      const column = index % columns;
      const row = Math.floor(index / columns);
      const x = gap + column * tileWidth;
      const y = gap + row * (tileHeight + titleHeight);
      const title = canvas.closest('.bg-slate-950')?.querySelector('p')?.textContent || `Pie ${index + 1}`;

      ctx.fillStyle = '#e2e8f0';
      ctx.fillText(title, x, y);
      ctx.drawImage(canvas, x, y + titleHeight, tileWidth - gap, tileHeight);
    });

    return output.toDataURL(format, format === 'image/png' ? undefined : 0.9);
  }

  function triggerDownload(dataUrl, fileName) {
    const anchor = document.createElement('a');
    anchor.href = dataUrl;
    anchor.download = fileName;
    anchor.click();
  }

  function buildSavedChartsKey() {
    const signature = `${config.fileName || 'session'}::${HEADERS.join('|')}`;
    return `insightanalytics:saved-charts:${encodeURIComponent(signature).slice(0, 180)}`;
  }

  function getSavedCharts() {
    try {
      const raw = localStorage.getItem(SAVED_CHARTS_KEY);
      const parsed = raw ? JSON.parse(raw) : [];
      return Array.isArray(parsed) ? parsed : [];
    } catch (error) {
      console.error('Saved charts read failed', error);
      return [];
    }
  }

  function persistSavedCharts(entries) {
    let snapshot = [...entries];
    while (snapshot.length > 0) {
      try {
        localStorage.setItem(SAVED_CHARTS_KEY, JSON.stringify(snapshot));
        return snapshot;
      } catch (error) {
        snapshot = snapshot.slice(0, -1);
      }
    }
    localStorage.removeItem(SAVED_CHARTS_KEY);
    return [];
  }

  async function saveChartToServer(entry) {
    try {
      const response = await fetch('/charts/save', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(entry),
      });
      const result = await response.json();
      if (!response.ok || !result.success) {
        throw new Error(result.message || 'Server save failed');
      }
      return result;
    } catch (error) {
      console.error('Report chart sync failed', error);
      return {
        success: false,
        message: error.message || 'Report sync failed',
      };
    }
  }

  function formatSavedDate(value) {
    if (!value) return '--';
    return new Intl.DateTimeFormat('en-IN', {
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date(value));
  }

  function renderSaveTag(label, value) {
    return `
      <span class="inline-flex items-center gap-1 rounded-full bg-slate-800 border border-slate-700 px-2 py-1">
        <span class="text-gray-500">${escapeHtmlSafe(label)}:</span>
        <span class="text-gray-200">${escapeHtmlSafe(value)}</span>
      </span>
    `;
  }

  function renderSavedStat(label, value, integerOnly = false) {
    return `
      <div class="rounded-xl border border-slate-800 bg-slate-900/70 px-3 py-2">
        <p class="text-[11px] text-gray-500">${escapeHtmlSafe(label)}</p>
        <p class="text-sm text-white mt-1">${integerOnly ? fmtInteger(value) : fmt(value)}</p>
      </div>
    `;
  }

  function renderSavedCharts(providedEntries) {
    const entries = Array.isArray(providedEntries) ? providedEntries : getSavedCharts();
    const grid = document.getElementById('savedChartsGrid');
    const empty = document.getElementById('savedChartsEmpty');

    if (!grid || !empty) return;

    empty.classList.toggle('hidden', entries.length > 0);
    grid.innerHTML = entries.map(entry => `
      <article class="bg-slate-950 border border-slate-800 rounded-2xl overflow-hidden">
        <div class="border-b border-slate-800 bg-slate-900">
          ${entry.imageData
            ? `<img src="${escapeAttrSafe(entry.imageData)}" alt="${escapeAttrSafe(entry.title || 'Saved chart')}" class="w-full h-56 object-contain bg-slate-950">`
            : '<div class="h-56 flex items-center justify-center text-sm text-gray-500">Preview unavailable</div>'}
        </div>
        <div class="p-4 space-y-3">
          <div class="flex items-start justify-between gap-3">
            <div>
              <p class="text-sm font-semibold text-white">${escapeHtmlSafe(entry.title || 'Saved chart')}</p>
              <p class="text-[11px] text-gray-500 mt-1">${formatSavedDate(entry.savedAt)}</p>
            </div>
            <div class="flex gap-2">
              <button onclick="downloadSavedChart('${escapeJsSafe(entry.id)}')"
                      class="bg-slate-800 hover:bg-slate-700 border border-slate-700 px-2.5 py-1.5 rounded-lg text-[11px] transition">
                Download
              </button>
              <button onclick="removeSavedChart('${escapeJsSafe(entry.id)}')"
                      class="bg-rose-500/10 hover:bg-rose-500/20 text-rose-300 border border-rose-500/30 px-2.5 py-1.5 rounded-lg text-[11px] transition">
                Delete
              </button>
            </div>
          </div>
          <div class="flex flex-wrap gap-2 text-[11px]">
            ${renderSaveTag('Type', humanizeChartType(entry.type))}
            ${entry.meta?.xAxis ? renderSaveTag('X', entry.meta.xAxis) : ''}
            ${entry.meta?.yAxis ? renderSaveTag('Y', entry.meta.yAxis) : ''}
            ${entry.meta?.aggregation ? renderSaveTag('Agg', humanizeAggregation(entry.meta.aggregation)) : ''}
            ${entry.meta?.pieMode ? renderSaveTag('Mode', capitalize(entry.meta.pieMode)) : ''}
            ${entry.meta?.fileName ? renderSaveTag('File', entry.meta.fileName) : ''}
          </div>
          ${entry.meta?.pieColumns?.length
            ? `<p class="text-xs text-gray-400">Columns: ${escapeHtmlSafe(entry.meta.pieColumns.join(', '))}</p>`
            : ''}
          ${entry.meta?.note
            ? `<p class="text-xs text-gray-400 leading-relaxed">${escapeHtmlSafe(entry.meta.note)}</p>`
            : ''}
          <div class="grid grid-cols-2 gap-2 text-xs">
            ${renderSavedStat('Average', entry.stats?.avg)}
            ${renderSavedStat('Std Dev', entry.stats?.stdDev)}
            ${renderSavedStat('Median', entry.stats?.median)}
            ${renderSavedStat('Mode', entry.stats?.mode)}
            ${renderSavedStat('Min', entry.stats?.min)}
            ${renderSavedStat('Max', entry.stats?.max)}
            ${renderSavedStat('Total', entry.stats?.sum)}
            ${renderSavedStat('Count', entry.stats?.count, true)}
          </div>
        </div>
      </article>
    `).join('');
  }

  function collectSaveMeta() {
    const aggregation = document.getElementById('aggregationMode').value;
    return {
      fileName: config.fileName,
      xAxis: currentType === 'pie' ? null : document.getElementById('xAxis').value,
      yAxis: currentType === 'pie'
        ? null
        : currentType === 'scatter'
          ? document.getElementById('yAxis').value
          : aggregation === 'count'
            ? 'Count'
            : document.getElementById('yAxis').value,
      aggregation: currentType === 'bar' || currentType === 'line' ? aggregation : null,
      sort: currentType === 'bar' || currentType === 'line' ? document.getElementById('sortMode').value : null,
      limit: resolveCategoryLimit(),
      pieMode: currentType === 'pie' ? currentPieMode : null,
      pieColumns: currentType === 'pie' ? selectedPieColumns.slice() : [],
      note: document.getElementById('chartMeta').textContent || '',
      sourceRows: APP.currentPayload?.sourceRows ?? config.totalRows,
      groupCount: APP.currentPayload?.groupCount ?? null,
      displayedCount: APP.currentPayload?.displayedCount ?? null,
      scope: config.chartScope,
    };
  }

  window.updateStats = function(values, xValues = []) {
    const nums = [];
    const pairedX = [];
    const pairedY = [];

    values.forEach((value, index) => {
      const numericValue = toNumber(value);
      if (numericValue == null) return;
      nums.push(numericValue);

      if (xValues.length === values.length) {
        const numericX = toNumber(xValues[index]);
        if (numericX != null) {
          pairedX.push(numericX);
          pairedY.push(numericValue);
        }
      }
    });

    if (nums.length === 0) {
      window.resetStats();
      return;
    }

    const stats = {
      count: nums.length,
      min: Math.min(...nums),
      max: Math.max(...nums),
      avg: nums.reduce((a, b) => a + b, 0) / nums.length,
      sum: nums.reduce((a, b) => a + b, 0),
      median: calcPercentile(nums, 50),
      mode: calcMode(nums),
      variance: calcVariance(nums),
      stdDev: calcStdDev(nums),
      q1: calcPercentile(nums, 25),
      q3: calcPercentile(nums, 75),
      p90: calcPercentile(nums, 90),
      growth: calcGrowth(nums),
      skewness: calcSkewness(nums),
      kurtosis: calcKurtosis(nums),
      correlation: pairedX.length >= 2 ? calcCorrelation(pairedX, pairedY) : null,
      forecast: calcForecast(nums),
    };

    document.getElementById('statMin').textContent = fmt(stats.min);
    document.getElementById('statMax').textContent = fmt(stats.max);
    document.getElementById('statAvg').textContent = fmt(stats.avg);
    document.getElementById('statSum').textContent = fmt(stats.sum);
    document.getElementById('statMedian').textContent = fmt(stats.median);
    document.getElementById('statMode').textContent = fmt(stats.mode);
    document.getElementById('statVariance').textContent = fmt(stats.variance);
    document.getElementById('statStdDev').textContent = fmt(stats.stdDev);
    document.getElementById('statQ1').textContent = fmt(stats.q1);
    document.getElementById('statQ3').textContent = fmt(stats.q3);
    document.getElementById('statP90').textContent = fmt(stats.p90);
    document.getElementById('statGrowth').textContent = stats.growth == null ? '--' : `${fmt(stats.growth)}%`;
    document.getElementById('statSkewness').textContent = fmt(stats.skewness);
    document.getElementById('statKurtosis').textContent = fmt(stats.kurtosis);
    document.getElementById('statCorrelation').textContent = fmt(stats.correlation);
    document.getElementById('statForecast').textContent = fmt(stats.forecast);

    APP.currentStats = stats;
  };

  window.resetStats = function() {
    [
      'statMin', 'statMax', 'statAvg', 'statSum', 'statMedian', 'statMode', 'statVariance',
      'statStdDev', 'statQ1', 'statQ3', 'statP90', 'statGrowth', 'statSkewness',
      'statKurtosis', 'statCorrelation', 'statForecast'
    ].forEach(id => {
      const element = document.getElementById(id);
      if (element) element.textContent = '--';
    });
    APP.currentStats = emptyStatsSnapshot();
  };

  window.renderPieColumnSelector = function() {
    const container = document.getElementById('pieColumnsList');
    if (!container) return;

    container.innerHTML = HEADERS.map(header => `
      <label class="flex items-center justify-between gap-3 bg-slate-800 border border-slate-700 rounded-lg px-3 py-2">
        <span class="text-sm text-gray-200 truncate">${escapeHtmlSafe(header)}</span>
        <input type="checkbox"
               value="${escapeAttrSafe(header)}"
               ${selectedPieColumns.includes(header) ? 'checked' : ''}
               onchange="togglePieColumn('${escapeJsSafe(header)}', this.checked)"
               class="w-4 h-4 accent-sky-400">
      </label>
    `).join('');
  };

  window.togglePieColumn = function(column, checked) {
    if (checked) {
      if (!selectedPieColumns.includes(column)) {
        selectedPieColumns.push(column);
      }
    } else {
      selectedPieColumns = selectedPieColumns.filter(item => item !== column);
    }
    window.buildChart();
  };

  window.setChartType = function(type) {
    activateChartType(type);
    window.buildChart();
  };

  window.setTheme = function(theme) {
    currentTheme = theme;
    window.buildChart();
  };

  window.setPieMode = function(mode) {
    currentPieMode = mode;
    ['separate', 'combined'].forEach(item => {
      document.getElementById('btn-pie-' + item).classList.remove('active');
    });
    document.getElementById('btn-pie-' + mode).classList.add('active');
    window.buildChart();
  };

  window.applyRecommendation = function(id) {
    const recommendation = SMART_RECOMMENDATIONS.find(item => (item.id || '') === id);
    if (!recommendation) {
      return;
    }

    if (recommendation.limit) {
      const limitSelect = document.getElementById('categoryLimit');
      if (limitSelect && Array.from(limitSelect.options).some(option => option.value === String(recommendation.limit))) {
        limitSelect.value = String(recommendation.limit);
      }
    }

    if (recommendation.chartType === 'pie') {
      currentPieMode = recommendation.pieMode || 'separate';
      ['separate', 'combined'].forEach(item => {
        document.getElementById('btn-pie-' + item).classList.toggle('active', item === currentPieMode);
      });
      selectedPieColumns = Array.isArray(recommendation.pieColumns)
        ? recommendation.pieColumns.filter(column => HEADERS.includes(column))
        : [];
      activateChartType('pie');
      window.renderPieColumnSelector();
      window.buildChart();
      return;
    }

    const xSelect = document.getElementById('xAxis');
    const ySelect = document.getElementById('yAxis');
    const aggregationSelect = document.getElementById('aggregationMode');
    const sortSelect = document.getElementById('sortMode');

    if (recommendation.xAxis && xSelect) {
      xSelect.value = recommendation.xAxis;
    }
    if (recommendation.yAxis && ySelect) {
      ySelect.value = recommendation.yAxis;
    }
    if (recommendation.aggregation && aggregationSelect) {
      aggregationSelect.value = recommendation.aggregation;
    }
    if (recommendation.sort && sortSelect) {
      sortSelect.value = recommendation.sort;
    }

    activateChartType(recommendation.chartType || 'bar');
    window.buildChart();
  };

  window.updateSuggested = function() {
    renderSchemaHighlights();
    renderSmartSuggestions();
  };

  window.buildChart = async function() {
    const requestId = ++APP.activeRequestId;
    syncControlVisibility();
    setMessage('Chart data load ho rahi hai...', 'info');
    setLoading(true);

    if (!HEADERS.length) {
      window.resetStats();
      setMessage('Pehle data upload karo, tab chart generate hoga.', 'error');
      setLoading(false);
      return;
    }

    const aggregation = document.getElementById('aggregationMode').value;
    const limit = resolveCategoryLimit();

    if (currentType === 'pie') {
      await window.buildPieCharts(requestId, limit);
      setLoading(false);
      return;
    }

    if (currentType === 'scatter' && !ensureScatterAxes()) {
      window.resetStats();
      setChartMeta('Scatter chart me sirf numeric X aur Y axis supported hain.');
      setMessage('Scatter chart ke liye kam se kam do numeric columns chahiye.', 'error');
      setLoading(false);
      return;
    }

    if (!NUMERIC_COLUMNS.length && aggregation !== 'count') {
      document.getElementById('aggregationMode').value = 'count';
    }

    const yAxis = ensureNumericYAxis();
    if (currentType !== 'scatter' && document.getElementById('aggregationMode').value !== 'count' && !yAxis) {
      window.resetStats();
      setMessage('Numeric Y-axis column nahi mila.', 'error');
      setLoading(false);
      return;
    }

    try {
      const params = new URLSearchParams({
        xAxis: document.getElementById('xAxis').value,
        chartType: currentType,
        aggregation: document.getElementById('aggregationMode').value,
        limit: String(limit),
        sort: document.getElementById('sortMode').value,
      });

      if (currentType === 'scatter' || document.getElementById('aggregationMode').value !== 'count') {
        params.set('yAxis', yAxis || '');
      }

      const payload = await fetchJson(`/charts/data?${params.toString()}`);
      if (requestId !== APP.activeRequestId) return;

      if (!payload.success) {
        APP.currentPayload = null;
        window.resetStats();
        setMessage(payload.message || 'Chart load nahi hui.', 'error');
        return;
      }

      APP.currentPayload = payload;
      renderCurrentChart(payload);
      if (typeof updateSuggested === 'function') {
        updateSuggested(payload.xAxis, payload.aggregation === 'count' ? 'Count' : payload.yAxis);
      }
      setMessage('', 'info');
    } catch (error) {
      console.error('Chart build failed', error);
      if (requestId === APP.activeRequestId) {
        APP.currentPayload = null;
        setMessage('Chart load nahi hui. Server ya data response check karo.', 'error');
      }
    } finally {
      if (requestId === APP.activeRequestId) {
        setLoading(false);
      }
    }
  };

  window.buildPieCharts = async function(requestId = ++APP.activeRequestId, limit = resolveCategoryLimit()) {
    const columns = selectedPieColumns.slice();
    const empty = document.getElementById('pieEmptyState');

    document.getElementById('chartTitle').textContent = columns.length
      ? `Pie Chart - ${columns.join(', ')}`
      : 'Pie Chart';

    if (!columns.length) {
      showPieArea();
      empty.classList.remove('hidden');
      document.getElementById('pieChartsGrid').innerHTML = '';
      APP.currentPayload = null;
      window.resetStats();
      setChartMeta('Pie chart ke liye kam se kam ek categorical column select karo.');
      setMessage('', 'info');
      return;
    }

    try {
      const params = new URLSearchParams({
        mode: currentPieMode,
        limit: String(limit || APP.pieDefaultLimit),
      });
      columns.forEach(column => params.append('columns', column));

      const payload = await fetchJson(`/charts/pie-data?${params.toString()}`);
      if (requestId !== APP.activeRequestId) return;

      if (!payload.success) {
        APP.currentPayload = null;
        window.resetStats();
        setMessage(payload.message || 'Pie chart load nahi hui.', 'error');
        return;
      }

      APP.currentPayload = payload;
      renderPiePayload(payload);
      if (typeof updateSuggested === 'function') {
        updateSuggested(columns[0], 'Frequency');
      }
      setMessage('', 'info');
    } catch (error) {
      console.error('Pie chart build failed', error);
      if (requestId === APP.activeRequestId) {
        APP.currentPayload = null;
        setMessage('Pie chart load nahi hui. Server ya data response check karo.', 'error');
      }
    }
  };

  window.downloadChart = async function() {
    const imageData = captureCurrentChartImage('image/png');
    if (!imageData) {
      setMessage('Download ke liye pehle chart generate karo.', 'error');
      return;
    }

    triggerDownload(
      imageData,
      `${sanitizeFileName(document.getElementById('chartTitle').textContent || 'chart')}.png`
    );
  };

  window.saveCurrentChart = async function() {
    if (!APP.currentPayload) {
      setMessage('Pehle chart generate karo, phir save option use karo.', 'error');
      return;
    }

    const imageData = captureCurrentChartImage('image/webp');
    if (!imageData) {
      setMessage('Chart image save nahi ho paayi.', 'error');
      return;
    }

    const entry = {
      id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      title: document.getElementById('chartTitle').textContent || 'Saved Chart',
      type: currentType,
      theme: currentTheme,
      savedAt: new Date().toISOString(),
      imageData,
      meta: collectSaveMeta(),
      stats: APP.currentStats,
    };

    const saved = persistSavedCharts([entry, ...getSavedCharts()].slice(0, APP.maxSavedCharts));
    renderSavedCharts(saved);
    const serverResult = await saveChartToServer(entry);
    setMessage(
      serverResult.success
        ? 'Graph save ho gaya. Saved Graphs aur Full Report dono me mil jayega.'
        : 'Graph browser me save ho gaya, lekin Full Report sync nahi hua: ' + serverResult.message,
      serverResult.success ? 'success' : 'error'
    );
  };

  window.downloadSavedChart = function(id) {
    const entry = getSavedCharts().find(item => item.id === id);
    if (!entry?.imageData) {
      setMessage('Saved graph image nahi mili.', 'error');
      return;
    }

    triggerDownload(
      entry.imageData,
      `${sanitizeFileName(entry.title || 'saved-chart')}.${entry.imageData.startsWith('data:image/png') ? 'png' : 'webp'}`
    );
  };

  window.removeSavedChart = function(id) {
    const updated = persistSavedCharts(getSavedCharts().filter(entry => entry.id !== id));
    renderSavedCharts(updated);
    setMessage('Saved graph remove kar diya gaya.', 'success');
  };

  window.clearSavedCharts = function() {
    localStorage.removeItem(SAVED_CHARTS_KEY);
    renderSavedCharts([]);
    setMessage('Saved graphs clear kar diye gaye.', 'success');
  };

  window.addEventListener('load', () => {
    renderSchemaHighlights();
    renderSmartSuggestions();
    renderSavedCharts();

    if (HEADERS.length > 0) {
      selectedPieColumns = HEADERS.slice(0, Math.min(2, HEADERS.length));
      hydrateYAxisOptions();
      window.renderPieColumnSelector();
      syncControlVisibility();
      ensureNumericYAxis();
      window.buildChart();
    } else {
      setChartMeta('Upload ke baad chart preview yahin show hogi.');
    }
  });
})();
