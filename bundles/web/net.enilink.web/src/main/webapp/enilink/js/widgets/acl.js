$(function() {
	function hookEvents(context) {
		$(".btn-delete-acl", context).click(function() {
			var self = $(this).closest('[about]');
			var resource = self.resourceAttr("about");
			if (resource) {
				enilink.rdf.removeResource(resource.toString(), function(success) {
					if (success) {
						self.remove();
					}
				});
			}
		});
	}
	hookEvents();

	$(".btn-add-acl").click(function(e) {
		function createAcl(target, agent, mode, cb) {
			enilink.rdf.updateTriples({
				"_:new-acl" : {
					"http://www.w3.org/ns/auth/acl#accessTo" : [ {
						value : target,
						type : "uri"
					} ],
					"http://www.w3.org/ns/auth/acl#agent" : [ {
						value : agent,
						type : "uri"
					} ],
					"http://www.w3.org/ns/auth/acl#mode" : [ {
						value : mode,
						type : "uri"
					} ]
				}
			}, cb);
		}

		function createQuery(keywords) {
			var template = $('<div data-lift="rdfa"><div class="search-patterns"></div><div class="agent" about="?agent" typeof="http://xmlns.com/foaf/0.1/Agent"></div></div>');
			template.find(".search-patterns").attr("data-value", keywords);
			return $("<div>").append(template).html();
		}

		var self = $(this);
		var editableOptions = {
			type : "typeaheadjs",
			clear : false,
			onblur : "ignore",
			// inputclass : "input-xxlarge",
			mode : "inline",
			toggle : "manual"
		};
		editableOptions = $.extend(editableOptions, {
			display : false,
			success : function(html) {
				if (html) {
					// insert element
				}
			},
			value : "",
			url : function(params) {
				enilink.render({
					what : createQuery(params.value)
				}, {
					model : enilink.param("model")
				}, function(html) {
					var agent = $(html).find(".agent").map(function() {
						return $(this).resourceAttr("about");
					}).filter(function() {
						return params.value == this.value.localPart();
					}).get(0);
					if (agent) {
						var mode = self.closest(".acl-widget").find(".acl-mode [resource]").resourceAttr("resource");
						if (!mode) {
							return;
						}
						createAcl(enilink.param("resource"), agent.value.toString(), mode.value.toString(), function(success) {
							if (!success) {
								return true;
							}
							enilink.render({
								what : '<div data-lift="rdfa"><span class="query"></span><div data-embed="widgets/acl" data-template="acl-content">' + //
								'<lift:bind-at name="acl.modeName"></lift:bind-at>' + //
								'<lift:bind-at name="acl.mode"><span resource="' + mode.value + '"></span></lift:bind-at>' + //
								'</div></div>'
							}, {
								model : enilink.param("model"),
								resource : enilink.param("resource")
							}, function(html) {
								var newContent = $(html).find('[data-t="acl-content"]');
								hookEvents(newContent);
								self.closest(".acl-widget").find('[data-t="acl-content"]').replaceWith(newContent);
							});
							return false;
						});
					}
				});
			}
		});
		var timeoutID = null;
		editableOptions.typeahead = [ {
			minLength : 2
		}, {
			source : function(query, cb) {
				if (timeoutID) {
					clearTimeout(timeoutID);
				}
				timeoutID = window.setTimeout(function() {
					enilink.render({
						what : createQuery(query)
					}, {
						model : enilink.param("model")
					}, function(html) {
						var items = $(html).find(".agent").map(function() {
							var r = $(this).resourceAttr("about");
							return {
								label : r.value.localPart(),
								content : r.value.localPart()
							};
						}).get();
						cb(items);
					});
				}, 500);
			},
			displayKey : function(v) {
				return v.content;
			},
			templates : {
				suggestion : function(v) {
					var t = $('<p />').css("white-space", "nowrap").text(v.label);
					return $("<span />").append(t);
				}
			}
		} ];
		self.editable(editableOptions);
		self.editable("toggle");
	});
});