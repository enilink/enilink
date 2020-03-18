/*
Enables the usage of bootstrap's notifications through Javascript.
A div #notification-area needs to be created where the notifications are going to be displayed by default.
*/
var notifications = notifications || {
		/*
		 * Show a notification. Default value are above in notificationOptDef
		 * 
		 * Ex: bootstrap.notify("Your item has been successfully saved"); Ex:
		 * bootstrap.notify("Your script cannot be saved. Please retry later.",
		 * {mode: "error"}) Ex: bootstrap.notify("Does it stink?", {closeTime:
		 * 0, actions: [ {label: "Yes", defaultAction: true, keys: "enter",
		 * extraClasses: "primary" callback: function(){console.log("Oh oh")}},
		 * {label: "No", keys: "esc", callback: function(){console.log("Oh
		 * great! I am happy to know that!")}} ]});
		 */
		notify : (function() {
			var notificationOptDef = {
				mode : "info", // Chose between: info(blue), warning(yellow),
								// success(green), error(red)
				attachTo : "#notification-area", // If you want the
													// notification to appear
													// somewhere else use this
													// selector.
				closeTime : 5000, // 0 is sticky, otherwise autoclose in X
									// msec, if there are actions this is set to
									// 0 by default
				actions : [], // Optional if you want to use a message block.
								// Ex: [{label: "Ok", callback: function
								// (){...}}, ... ]
				closePrevious : false, // If true close the previous
										// notifications if there are any still
										// open on the screen.
				extraClasses : "" // space separated list of extra classes to
									// add to the notification
			}

			var nextNotificationId = 1; // Notification Id of the current
										// notification.

			function unbindkeys($this) {
				var id = $this.closest(".alert-message").attr('id');
				$(document).unbind("keyup." + id);
			}

			return function(message, opts) {
				var settings = $.extend({}, notificationOptDef, opts);

				// Each notification has its own id
				var id = "notificationBox" + (nextNotificationId++);

				var alertActions = '';
				var blockMessageClass = '';
				if (settings.actions.length > 0) {
					pub.modalBackdrop.show();

					for (i in settings.actions) {
						// This extraClasses is different than the
						// settings.extraClasses. This is for each button
						alertActions += '<a class="btn small '
								+ (settings.actions[i].extraClasses ? settings.actions[i].extraClasses
										: '') + ' notification-action-' + i
								+ '" href="#">' + settings.actions[i].label
								+ "</a> "
					}

					alertActions = '<div class="alert-actions">' + alertActions
							+ '</div>'
					blockMessageClass = " block-message";
				}

				var html = '<div id="' + id
						+ '" class="fl-notification alert alert-message fade in '
						+ settings.mode + blockMessageClass + " "
						+ settings.extraClasses + '" data-alert="alert">'
						+ '<a class="close" data-dismiss="alert" href="#">×</a><p>' + message
						+ '</p>' + alertActions + '</div>';

				// remove any previous notifications
				if (settings.closePrevious) {
					$('.fl-notification').remove();
				}

				$(settings.attachTo).append($(html));
				var $this = $("#" + id);
				$this.alert();
				$(".close", $this).click(function() {
					unbindkeys($(this));
					notifications.modalBackdrop.hide();
				});

				for (i in settings.actions) {
					var _action = settings.actions[i];
					(function(action, keys) {
						var $action = $("#" + id + " .notification-action-" + i);
						if (action.defaultAction)
							$action.focus();
						$action.click(function(e) {
							e.preventDefault();
							if (action.callback)
								action.callback();
							$(this).closest(".alert-message").find(".close")
									.click();
						});

						if (action.keys) {
							$(document).bind(
									"keyup." + id,
									action.keys,
									function() {
										console.log("Action triggered:",
												$action);
										$action.click();
									});
						}
					})(_action);
				}

				if (settings.actions.length === 0 && // Only if there are no
														// actions
				settings.closeTime) {
					(function(id) {
						var timerId = setTimeout(function() {
							$("#" + id).find(".close").click();
						}, settings.closeTime);
					})(id);
				}
			}
		})(),
		/*
		 * Open a block message as notification with Yes (with associated
		 * opts.yesAction) and No (with associated opts.noAction) buttons
		 */
		notifyYesNo : function(message, opts) {
			var notificationYesNo = {
				actions : [ {
					label : "Yes",
					defaultAction : true,
					keys : "return",
					callback : opts.yesAction
				}, {
					label : "No",
					keys : "esc",
					callback : opts.noAction
				} ]
			}

			var settings = $.extend({}, opts, notificationYesNo);
			pub.notify(message, settings);
		},

		/*
		 * Open a block message as notification with Ok (with associated
		 * opts.okAction)
		 */
		notifyOk : function(message, opts) {
			var notificationOk = {
				actions : [ {
					label : "Ok",
					defaultAction : true,
					keys : "return",
					callback : opts.okAction
				} ]
			}

			var settings = $.extend({}, opts, notificationOk);
			pub.notify(message, settings);
		},

		/*
		 * Create a very light trasparent backdrop for modal actions
		 * 
		 * Example of use: bootstrap.modalBackdrop.show();
		 * bootstrap.modalBackdrop.hide(); bootstrap.modalBackdrop.css({opacity:
		 * 0.5}); bootstrap.modalBackdrop.css({"background-color" : "#ff0"});
		 */
		modalBackdrop : (function() {
			var $backdrop = $(
					'<div class="fl-modalBackdrop" style="position: fixed;top:0;left:0;right:0;bottom:0;z-index:9998;opacity: 0.15;background-color:#fff;display:none"/>')
					.appendTo(document.body);

			return $backdrop;
		})()
	};
