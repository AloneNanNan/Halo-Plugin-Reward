(function () {
  'use strict';

  // 页面模板与主题片段可能都引入本脚本，重复执行时直接退出，避免重复绑定监听
  if (window.RewardModal) {
    return;
  }

  var API_BASE = '/apis/api.reward.nanzhiy.cn/v1alpha1';
  var CONFIG_URL = API_BASE + '/public/config';
  var SUBMIT_URL = API_BASE + '/records';

  var CAPTCHA_URL = API_BASE + '/public/captcha';

  var STYLE_ID = 'reward-modal-style';
  var MODAL_ID = 'reward-modal';

  var configCache = null;
  var configCachedAt = 0;
  var configPromise = null;
  // 站长修改开关后无需访客刷新页面即可生效
  var CONFIG_TTL = 60 * 1000;

  var STYLE = [
    '.rwm-mask{position:fixed;inset:0;z-index:9999;display:flex;align-items:center;justify-content:center;',
    'padding:1.25rem;background:rgba(15,15,18,.55);backdrop-filter:blur(2px);}',
    '.rwm-mask[hidden]{display:none;}',
    '.rwm-dialog{position:relative;width:100%;max-width:480px;max-height:88vh;overflow-y:auto;',
    'padding:1.5rem;border-radius:20px;background:#fff;color:#18181b;',
    'box-shadow:0 24px 60px -20px rgba(0,0,0,.45);font-size:15px;line-height:1.6;}',
    '.rwm-title{margin:0 0 1.1rem;font-size:1.05rem;font-weight:600;}',
    '.rwm-close{position:absolute;top:.75rem;right:.75rem;width:32px;height:32px;border:none;',
    'border-radius:10px;background:transparent;color:#71717a;font-size:1.2rem;line-height:1;cursor:pointer;}',
    '.rwm-close:hover{background:#f4f4f5;color:#18181b;}',
    '.rwm-form{display:grid;grid-template-columns:1fr 1fr;gap:.8rem .75rem;}',
    '.rwm-field{display:block;min-width:0;margin-bottom:0;}',
    '.rwm-field--full{grid-column:1 / -1;}',
    '.rwm-submit,.rwm-msg{grid-column:1 / -1;}',
    '.rwm-label{display:block;margin-bottom:.3rem;font-size:.85rem;font-weight:500;}',
    '.rwm-req{padding:0 .35rem;border-radius:4px;background:rgba(184,137,43,.14);color:#b8892b;font-size:.7rem;}',
    '.rwm-opt{color:#8e8e96;font-size:.72rem;font-weight:400;}',
    '.rwm-input{width:100%;box-sizing:border-box;padding:.6rem .8rem;border:1px solid #e4e4e7;',
    'border-radius:12px;background:#fff;color:#18181b;font-size:.9rem;font-family:inherit;line-height:1.5;}',
    '.rwm-input:focus{border-color:#18181b;box-shadow:0 0 0 3px rgba(128,128,128,.14);outline:none;}',
    'textarea.rwm-input{min-height:4rem;resize:vertical;}',
    '.rwm-captcha-row{display:flex;align-items:center;gap:.6rem;}',
    '.rwm-captcha{height:44px;width:132px;flex:none;border:1px solid #e4e4e7;border-radius:10px;',
    'cursor:pointer;background:#fff;object-fit:contain;}',
    '.rwm-captcha-hint{color:#8e8e96;font-size:.72rem;cursor:pointer;}',
    '.rwm-submit{width:100%;margin-top:.4rem;padding:.75rem 1rem;border:none;border-radius:12px;',
    'background:#18181b;color:#fff;font-size:.92rem;font-weight:500;cursor:pointer;}',
    '.rwm-submit:hover{background:#2f2f33;}',
    '.rwm-submit:disabled{opacity:.55;cursor:not-allowed;}',
    '.rwm-msg{min-height:1.1rem;margin:.6rem 0 0;font-size:.82rem;color:#8e8e96;}',
    '.rwm-msg.is-error{color:#d93026;}',
    '@media (max-width:430px){',
    '.rwm-form{grid-template-columns:1fr;}',
    '}',
    '@media (prefers-color-scheme:dark){',
    '.rwm-dialog{background:#1b1b1e;color:#f4f4f5;}',
    '.rwm-input{background:#1b1b1e;color:#f4f4f5;border-color:#2a2a2f;}',
    '.rwm-close:hover{background:#26262b;color:#f4f4f5;}',
    '.rwm-submit{background:#f4f4f5;color:#18181b;}',
    '.rwm-submit:hover{background:#fff;}',
    '}',
    '@media (prefers-reduced-motion:no-preference){',
    '.rwm-dialog{animation:rwm-in .18s ease-out;}',
    '@keyframes rwm-in{from{opacity:0;transform:translateY(8px);}to{opacity:1;transform:none;}}',
    '}'
  ].join('');

  function injectStyle() {
    if (document.getElementById(STYLE_ID)) {
      return;
    }
    var style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = STYLE;
    document.head.appendChild(style);
  }

  function loadConfig() {
    if (configCache && Date.now() - configCachedAt < CONFIG_TTL) {
      return Promise.resolve(configCache);
    }
    if (configPromise) {
      return configPromise;
    }
    configPromise = fetch(CONFIG_URL)
      .then(function (response) {
        if (!response.ok) {
          throw new Error('配置加载失败');
        }
        return response.json();
      })
      .then(function (config) {
        configCache = config;
        configCachedAt = Date.now();
        configPromise = null;
        return config;
      })
      .catch(function (error) {
        configPromise = null;
        throw error;
      });
    return configPromise;
  }

  function field(label, required, control, full) {
    var wrap = document.createElement('label');
    wrap.className = full ? 'rwm-field rwm-field--full' : 'rwm-field';

    var caption = document.createElement('span');
    caption.className = 'rwm-label';
    caption.textContent = label;

    if (required) {
      var badge = document.createElement('span');
      badge.className = 'rwm-req';
      badge.textContent = '必填';
      caption.appendChild(document.createTextNode(' '));
      caption.appendChild(badge);
    }

    wrap.appendChild(caption);
    wrap.appendChild(control);
    return wrap;
  }

  function input(name, type, placeholder, extra) {
    var el = document.createElement('input');
    el.className = 'rwm-input';
    el.name = name;
    el.type = type || 'text';
    if (placeholder) {
      el.placeholder = placeholder;
    }
    if (extra) {
      Object.keys(extra).forEach(function (key) {
        el.setAttribute(key, extra[key]);
      });
    }
    return el;
  }

  function textarea(name, placeholder, maxLength) {
    var el = document.createElement('textarea');
    el.className = 'rwm-input';
    el.name = name;
    el.placeholder = placeholder || '';
    if (maxLength) {
      el.maxLength = maxLength;
    }
    return el;
  }

  function select(name, options) {
    var el = document.createElement('select');
    el.className = 'rwm-input';
    el.name = name;
    options.forEach(function (item) {
      var option = document.createElement('option');
      option.value = item.value;
      option.textContent = item.label;
      el.appendChild(option);
    });
    return el;
  }

  function buildDialog(config) {
    var mask = document.createElement('div');
    mask.className = 'rwm-mask';
    mask.id = MODAL_ID;
    mask.hidden = true;

    var dialog = document.createElement('div');
    dialog.className = 'rwm-dialog';
    dialog.setAttribute('role', 'dialog');
    dialog.setAttribute('aria-modal', 'true');

    var close = document.createElement('button');
    close.type = 'button';
    close.className = 'rwm-close';
    close.setAttribute('aria-label', '关闭');
    close.textContent = '×';
    dialog.appendChild(close);

    var title = document.createElement('h2');
    title.className = 'rwm-title';
    title.textContent = '填写打赏信息';
    dialog.appendChild(title);

    var form = document.createElement('form');
    form.className = 'rwm-form';
    form.noValidate = true;

    form.appendChild(field('打赏方式', false, select('method', [
      { value: 'wechat', label: '微信' },
      { value: 'alipay', label: '支付宝' }
    ])));

    form.appendChild(field('打赏金额', true,
      input('amount', 'number', (config.currencySymbol || '¥') + ' 请输入金额', {
        min: '0.01', step: '0.01', inputmode: 'decimal'
      })));

    form.appendChild(field('昵称', true,
      input('nickname', 'text', '展示在赞助者墙上的名字', { maxlength: '30' })));

    var contactField = field('联系方式', false,
      input('contact', 'text', '邮箱或其他联系方式', { maxlength: '100' }));
    var contactHint = document.createElement('span');
    contactHint.className = 'rwm-opt';
    contactHint.textContent = ' 选填，仅站长可见';
    contactField.querySelector('.rwm-label').appendChild(contactHint);
    form.appendChild(contactField);

    if (config.enableTransactionNo) {
      form.appendChild(field('交易单号', true,
        input('transactionNo', 'text', '便于站长核对收款', { maxlength: '64' }), true));
    }

    if (config.enableAvatar) {
      var avatarField = field('头像链接', false,
        input('avatar', 'url', 'https:// 图片地址，会展示在赞助者墙', { maxlength: '500' }), true);
      var hint = document.createElement('span');
      hint.className = 'rwm-opt';
      hint.textContent = ' 选填';
      avatarField.querySelector('.rwm-label').appendChild(hint);
      form.appendChild(avatarField);
    }

    if (config.allowMessage) {
      var messageField = field('留言', false, textarea('message', '想说的话', 200), true);
      var messageHint = document.createElement('span');
      messageHint.className = 'rwm-opt';
      messageHint.textContent = ' 选填';
      messageField.querySelector('.rwm-label').appendChild(messageHint);
      form.appendChild(messageField);
    }

    if (config.enableCaptcha) {
      var captchaField = document.createElement('div');
      captchaField.className = 'rwm-field rwm-field--full';

      var captchaLabel = document.createElement('span');
      captchaLabel.className = 'rwm-label';
      captchaLabel.textContent = '验证码 ';
      var captchaBadge = document.createElement('span');
      captchaBadge.className = 'rwm-req';
      captchaBadge.textContent = '必填';
      captchaLabel.appendChild(captchaBadge);
      captchaField.appendChild(captchaLabel);

      var captchaRow = document.createElement('div');
      captchaRow.className = 'rwm-captcha-row';

      var captchaInput = input('captchaCode', 'text', '图中字符', { maxlength: '6' });
      captchaRow.appendChild(captchaInput);

      var captchaImg = document.createElement('img');
      captchaImg.className = 'rwm-captcha';
      captchaImg.alt = '验证码';
      captchaImg.title = '点击刷新';
      captchaImg.addEventListener('click', function () {
        refreshCaptcha(form);
      });
      captchaRow.appendChild(captchaImg);

      captchaField.appendChild(captchaRow);

      var captchaHint = document.createElement('span');
      captchaHint.className = 'rwm-captcha-hint';
      captchaHint.textContent = '看不清？点击图片刷新';
      captchaHint.addEventListener('click', function () {
        refreshCaptcha(form);
      });
      captchaField.appendChild(captchaHint);

      form.appendChild(captchaField);
      form.__captcha = { img: captchaImg, input: captchaInput, hint: captchaHint };
    }

    var submit = document.createElement('button');
    submit.type = 'submit';
    submit.className = 'rwm-submit';
    submit.textContent = '提交打赏信息';
    form.appendChild(submit);

    var message = document.createElement('p');
    message.className = 'rwm-msg';
    message.setAttribute('role', 'status');
    form.appendChild(message);

    if (config.requireLogin && !config.loggedIn) {
      mask.dataset.needLogin = 'true';
      submit.disabled = true;
      showMessage(message, '请先登录后再提交打赏信息', true);
    }

    dialog.appendChild(form);
    mask.appendChild(dialog);

    close.addEventListener('click', closeModal);
    mask.addEventListener('click', function (event) {
      if (event.target === mask) {
        closeModal();
      }
    });
    form.addEventListener('submit', function (event) {
      handleSubmit(event, form, submit, message);
    });

    document.body.appendChild(mask);
    return mask;
  }

  function refreshCaptcha(form) {
    var captcha = form.__captcha;
    if (!captcha) {
      return;
    }
    fetch(CAPTCHA_URL)
      .then(function (response) {
        if (!response.ok) {
          throw new Error('验证码加载失败');
        }
        return response.json();
      })
      .then(function (data) {
        captcha.img.dataset.id = data.id;
        captcha.img.src = data.image;
        captcha.input.value = '';
      })
      .catch(function () {
        if (captcha.hint) {
          captcha.hint.textContent = '刷新过于频繁，请稍后再试';
          setTimeout(function () {
            captcha.hint.textContent = '看不清？点击图片刷新';
          }, 2500);
        }
      });
  }

  function showMessage(node, text, isError) {
    node.textContent = text;
    node.classList.toggle('is-error', !!isError);
  }

  function handleSubmit(event, form, submitButton, messageNode) {
    event.preventDefault();
    if (submitButton.disabled) {
      return;
    }
    var amount = parseFloat(form.elements.amount.value);
    var nickname = (form.elements.nickname.value || '').trim();
    var transactionNo = form.elements.transactionNo;
    var payload = {
      amount: amount,
      nickname: nickname,
      method: form.elements.method.value
    };

    if (!amount || amount <= 0) {
      showMessage(messageNode, '请填写打赏金额', true);
      form.elements.amount.focus();
      return;
    }
    if (!nickname) {
      showMessage(messageNode, '请填写昵称', true);
      form.elements.nickname.focus();
      return;
    }
    if (transactionNo && !transactionNo.value.trim()) {
      showMessage(messageNode, '请填写交易单号', true);
      transactionNo.focus();
      return;
    }

    var captcha = form.__captcha;
    if (captcha) {
      var captchaCode = captcha.input.value.trim();
      if (!captchaCode) {
        showMessage(messageNode, '请填写验证码', true);
        captcha.input.focus();
        return;
      }
      payload.captchaId = captcha.img.dataset.id;
      payload.captchaCode = captchaCode;
    }

    ['message', 'contact', 'transactionNo', 'avatar'].forEach(function (name) {
      var el = form.elements[name];
      if (el && el.value.trim()) {
        payload[name] = el.value.trim();
      }
    });

    submitButton.disabled = true;
    showMessage(messageNode, '提交中…', false);

    fetch(SUBMIT_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
      .then(function (response) {
        return response.json().catch(function () {
          return {};
        }).then(function (data) {
          if (!response.ok) {
            throw new Error(data.message || '提交失败，请稍后再试');
          }
          return data;
        });
      })
      .then(function (data) {
        showMessage(messageNode, data.message || '提交成功', false);
        form.reset();
        submitButton.disabled = true;
        setTimeout(closeModal, 1400);
      })
      .catch(function (error) {
        showMessage(messageNode, error.message || '提交失败，请稍后再试', true);
        submitButton.disabled = false;
        refreshCaptcha(form);
      });
  }

  function closeModal() {
    var mask = document.getElementById(MODAL_ID);
    if (mask) {
      mask.hidden = true;
    }
    document.body.style.overflow = '';
  }

  function openModal() {
    injectStyle();
    return loadConfig()
      .then(function (config) {
        var mask = document.getElementById(MODAL_ID);
        if (!mask) {
          mask = buildDialog(config);
        }
        mask.hidden = false;
        document.body.style.overflow = 'hidden';
        // 弹窗复用同一个 DOM，重新打开时需要恢复被上一次提交禁用的按钮与提示
        var needLogin = mask.dataset.needLogin === 'true';
        var submitButton = mask.querySelector('.rwm-submit');
        if (submitButton) {
          submitButton.disabled = needLogin;
        }
        var messageNode = mask.querySelector('.rwm-msg');
        if (messageNode) {
          messageNode.textContent = needLogin ? '请先登录后再提交打赏信息' : '';
          messageNode.classList.toggle('is-error', needLogin);
        }
        var formEl = mask.querySelector('.rwm-form');
        if (formEl && formEl.__captcha) {
          refreshCaptcha(formEl);
        }
        var firstInput = mask.querySelector('.rwm-input');
        if (firstInput) {
          firstInput.focus();
        }
      })
      .catch(function () {
        window.alert('打赏功能暂时不可用，请稍后再试');
      });
  }

  document.addEventListener('keydown', function (event) {
    var mask = document.getElementById(MODAL_ID);
    // 只在弹窗打开时响应 ESC，避免误清掉主题其它弹层的滚动锁
    if (event.key === 'Escape' && mask && !mask.hidden) {
      closeModal();
    }
  });

  document.addEventListener('click', function (event) {
    if (!(event.target instanceof Element)) {
      return;
    }
    var trigger = event.target.closest('[data-reward-open]');
    if (trigger) {
      event.preventDefault();
      openModal();
    }
  });

  window.RewardModal = {
    open: openModal,
    close: closeModal
  };
})();
