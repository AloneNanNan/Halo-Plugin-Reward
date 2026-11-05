(function () {
  'use strict';

  var API_BASE = '/apis/api.reward.nanzhiy.cn/v1alpha1';
  var WALL_URL = API_BASE + '/public/wall';

  var root = document.querySelector('.reward-page');
  if (!root) {
    return;
  }

  var currency = root.dataset.currency || '¥';
  var pageSize = parseInt(root.dataset.pageSize || '10', 10) || 10;
  var qrImages = Array.prototype.slice.call(root.querySelectorAll('.reward-qr'));
  var wallList = root.querySelector('.reward-wall-list');
  var wallEmpty = root.querySelector('.reward-wall-empty');
  var moreButton = root.querySelector('.reward-more');
  var lightbox = root.querySelector('.reward-lightbox');
  var lightboxImg = root.querySelector('.reward-lightbox-img');

  var currentPage = 1;
  var loadedCount = 0;

  function formatAmount(value) {
    var number = Number(value);
    if (isNaN(number)) {
      return '';
    }
    return currency + number.toFixed(2);
  }

  qrImages.forEach(function (image) {
    image.addEventListener('click', function () {
      if (!lightbox || !lightboxImg) {
        return;
      }
      lightboxImg.src = image.src;
      lightbox.hidden = false;
    });
  });

  if (lightbox) {
    lightbox.addEventListener('click', function () {
      lightbox.hidden = true;
    });
  }

  function buildWallItem(item) {
    var li = document.createElement('li');
    li.className = 'reward-wall-item';

    var head = document.createElement('div');
    head.className = 'reward-wall-head';

    if (item.avatar) {
      var avatar = document.createElement('img');
      avatar.className = 'reward-wall-avatar';
      avatar.src = item.avatar;
      avatar.alt = '';
      avatar.loading = 'lazy';
      head.appendChild(avatar);
    } else {
      var initial = document.createElement('span');
      initial.className = 'reward-wall-avatar reward-wall-avatar--text';
      initial.textContent = item.initial || (item.nickname || '*').slice(0, 1);
      head.appendChild(initial);
    }

    var info = document.createElement('div');
    info.className = 'reward-wall-info';

    var name = document.createElement('span');
    name.className = 'reward-wall-name';
    name.textContent = item.nickname || '匿名读者';
    info.appendChild(name);

    if (item.createdAt) {
      var time = document.createElement('time');
      time.className = 'reward-wall-time';
      time.textContent = item.createdAt;
      info.appendChild(time);
    }

    head.appendChild(info);

    if (item.amount !== null && item.amount !== undefined) {
      var amount = document.createElement('span');
      amount.className = 'reward-wall-amount';
      amount.textContent = formatAmount(item.amount);
      head.appendChild(amount);
    }

    li.appendChild(head);

    if (item.message) {
      var message = document.createElement('p');
      message.className = 'reward-wall-message';
      message.textContent = item.message;
      li.appendChild(message);
    }

    return li;
  }

  if (moreButton) {
    var moreLabel = moreButton.textContent;
    moreButton.addEventListener('click', function () {
      var nextPage = currentPage + 1;
      moreButton.disabled = true;
      fetch(WALL_URL + '?page=' + nextPage + '&size=' + pageSize)
        .then(function (response) {
          if (!response.ok) {
            throw new Error('加载失败');
          }
          return response.json();
        })
        .then(function (data) {
          var items = data.items || [];
          currentPage = nextPage;
          items.forEach(function (item) {
            if (wallList) {
              wallList.appendChild(buildWallItem(item));
            }
            loadedCount += 1;
          });
          if (loadedCount >= (data.total || 0)) {
            moreButton.hidden = true;
          }
          if (wallEmpty && loadedCount > 0) {
            wallEmpty.hidden = true;
          }
          moreButton.textContent = moreLabel;
          moreButton.disabled = false;
        })
        .catch(function () {
          moreButton.textContent = '加载失败，点击重试';
          moreButton.disabled = false;
        });
    });
  }

  if (wallList) {
    loadedCount = wallList.children.length;
  }
})();
